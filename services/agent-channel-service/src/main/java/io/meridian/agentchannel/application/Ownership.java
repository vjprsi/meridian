package io.meridian.agentchannel.application;

import io.meridian.agentchannel.application.Downstream.Query;
import io.meridian.agentchannel.config.AgentChannelProperties;
import io.meridian.common.error.ApiException;
import io.meridian.web.security.AgentContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.JsonNode;

/**
 * Object-level authorization for the agent channel. Every object an agent request references is fetched from its
 * owning service and checked against the signed-in agent before anything is shown or done. An object of another agent
 * answers 404 {@code not_found} exactly like a missing one (existence is never revealed); the refusal is audited and
 * published ({@code channels.agent_action.denied}).
 *
 * <ul>
 *   <li>Outlet, device: {@code agent_id}. Till and cash request: their outlet. Float request:
 *       {@code requesting_agent_id}. Commission earning: {@code beneficiary_agent_id}. Offline batch: {@code agent_id}.
 *       Onboarding application: {@code agent_id} and channel AGENT; its documents: owner ONBOARDING_APPLICATION of
 *       that application.</li>
 *   <li>Wallet transaction: {@code agent_id} (cash-in / cash-out) or the agent's float / commission wallet as payer or
 *       payee. Bill payment and top-up: paid from the agent's float wallet.</li>
 *   <li>Hierarchy: a direct sub-agent ({@code parent_agent_id} = the agent) may be read (profile, KPIs) when
 *       {@code meridian.agent-channel.hierarchy-read} is on; nothing of a sub-agent can be changed.</li>
 * </ul>
 */
@Component
public class Ownership {
    static final String AGENTS = "agent-network-service";
    private static final String AGENT_ATTRIBUTE = Ownership.class.getName() + ".agent";

    private final Downstream down;
    private final ActionAudit audit;
    private final AgentChannelProperties props;

    public Ownership(Downstream down, ActionAudit audit, AgentChannelProperties props) {
        this.down = down;
        this.audit = audit;
        this.props = props;
    }

    public static UUID me() {
        return AgentContext.agentId();
    }

    public static boolean mine(JsonNode row, String field) {
        return me().toString().equals(row.path(field).asString(null));
    }

    // ------------------------------------------------------------------ the agent

    /**
     * The signed-in agent's record (fetched once per request). An agent id that does not exist, or a terminated agent,
     * gets 403 {@code agent_not_found} / {@code agent_terminated}: the token names no usable agent.
     */
    public JsonNode agent() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        Object cached = attrs == null ? null : attrs.getAttribute(AGENT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (cached instanceof JsonNode n) {
            return n;
        }
        JsonNode agent;
        try {
            agent = down.get(AGENTS, "/v1/agents/" + me());
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw new ApiException(403, "agent_not_found", "The signed-in agent does not exist in this tenant");
            }
            throw e;
        }
        if ("TERMINATED".equals(agent.path("status").asString(""))) {
            throw new ApiException(403, "agent_terminated", "The agency of the signed-in agent was terminated");
        }
        if (attrs != null) {
            attrs.setAttribute(AGENT_ATTRIBUTE, agent, RequestAttributes.SCOPE_REQUEST);
        }
        return agent;
    }

    /** The signed-in agent, which must be ACTIVE to move money or change anything (422 agent_not_active). */
    public JsonNode activeAgent() {
        JsonNode agent = agent();
        if (!"ACTIVE".equals(agent.path("status").asString(""))) {
            throw ApiException.unprocessable("agent_not_active", "The agent is " + agent.path("status").asString());
        }
        return agent;
    }

    public String currency() {
        return agent().path("currency").asString();
    }

    /** The agent's float wallet (422 no_float_wallet before activation). */
    public UUID floatWallet() {
        String id = agent().path("float_wallet_id").asString(null);
        if (id == null || id.isBlank()) {
            throw ApiException.unprocessable("no_float_wallet", "The agent has no float wallet yet");
        }
        return UUID.fromString(id);
    }

    /** The agent's commission wallet (422 no_commission_wallet before activation). */
    public UUID commissionWallet() {
        String id = agent().path("commission_wallet_id").asString(null);
        if (id == null || id.isBlank()) {
            throw ApiException.unprocessable("no_commission_wallet", "The agent has no commission wallet yet");
        }
        return UUID.fromString(id);
    }

    /** Wallets of the agent itself (float, commission). */
    public Set<String> myWallets() {
        Set<String> out = new HashSet<>();
        JsonNode a = agent();
        for (String f : List.of("float_wallet_id", "commission_wallet_id")) {
            String v = a.path(f).asString(null);
            if (v != null && !v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }

    /** All outlets of the agent. */
    public List<JsonNode> outlets() {
        return down.all(AGENTS, "/v1/agent-outlets", Query.of("agent_id", me()), 500).stream()
                .filter(o -> mine(o, "agent_id")).toList();
    }

    // ------------------------------------------------------------------ objects

    public JsonNode outlet(UUID id, String action) {
        return owned(AGENTS, "/v1/agent-outlets/" + id, "OUTLET", id, action, r -> mine(r, "agent_id"));
    }

    /** An ACTIVE outlet of the agent (422 outlet_not_active otherwise). */
    public JsonNode activeOutlet(UUID id, String action) {
        JsonNode outlet = outlet(id, action);
        if (!"ACTIVE".equals(outlet.path("status").asString(""))) {
            throw ApiException.unprocessable("outlet_not_active", "The outlet is " + outlet.path("status").asString());
        }
        return outlet;
    }

    public JsonNode device(UUID id, String action) {
        return owned(AGENTS, "/v1/agent-devices/" + id, "DEVICE", id, action, r -> mine(r, "agent_id"));
    }

    public JsonNode till(UUID id, String action) {
        JsonNode till = fetch(AGENTS, "/v1/agent-tills/" + id, "TILL", id);
        throughOutlet(till, "agent_outlet_id", action, "TILL", id);
        return till;
    }

    public JsonNode cashRequest(UUID id, String action) {
        JsonNode req = fetch(AGENTS, "/v1/cash-management-requests/" + id, "CASH_REQUEST", id);
        throughOutlet(req, "agent_outlet_id", action, "CASH_REQUEST", id);
        return req;
    }

    public JsonNode floatRequest(UUID id, String action) {
        return owned(AGENTS, "/v1/agent-float-requests/" + id, "FLOAT_REQUEST", id, action,
                r -> mine(r, "requesting_agent_id"));
    }

    /** A transaction the agent took part in: as the agent (cash-in / cash-out) or through its own wallets. */
    public JsonNode transaction(UUID id, String action) {
        Set<String> wallets = myWallets();
        return owned("wallet-service", "/v1/wallet-transactions/" + id, "TRANSACTION", id, action,
                r -> mine(r, "agent_id") || wallets.contains(r.path("payer_wallet_id").asString(""))
                        || wallets.contains(r.path("payee_wallet_id").asString("")));
    }

    public boolean isMyTransaction(JsonNode r) {
        Set<String> wallets = myWallets();
        return mine(r, "agent_id") || wallets.contains(r.path("payer_wallet_id").asString(""))
                || wallets.contains(r.path("payee_wallet_id").asString(""));
    }

    public JsonNode billPayment(UUID id, String action) {
        String wallet = floatWalletOrNull();
        return owned("biller-service", "/v1/bill-payments/" + id, "BILL_PAYMENT", id, action,
                r -> wallet != null && wallet.equals(r.path("payer_wallet_id").asString(null)));
    }

    public JsonNode topup(UUID id, String action) {
        String wallet = floatWalletOrNull();
        return owned("biller-service", "/v1/airtime-topups/" + id, "AIRTIME_TOPUP", id, action,
                r -> wallet != null && wallet.equals(r.path("payer_wallet_id").asString(null)));
    }

    public JsonNode application(UUID id, String action) {
        return owned("onboarding-service", "/v1/onboarding/applications/" + id, "APPLICATION", id, action,
                r -> mine(r, "agent_id") && "AGENT".equals(r.path("channel").asString(null)));
    }

    /** A document of one of the agent's applications (the application itself already checked by the caller). */
    public JsonNode applicationDocument(UUID applicationId, UUID documentId, String action) {
        return owned("document-service", "/v1/documents/" + documentId, "DOCUMENT", documentId, action,
                r -> "ONBOARDING_APPLICATION".equals(r.path("owner_type").asString(null))
                        && applicationId.toString().equals(r.path("owner_id").asString(null)));
    }

    public JsonNode offlineBatch(UUID id, String action) {
        return owned("onboarding-service", "/v1/onboarding/offline-batches/" + id, "OFFLINE_BATCH", id, action,
                r -> mine(r, "agent_id"));
    }

    /** A direct sub-agent, readable only when the hierarchy allows it (404 otherwise). */
    public JsonNode subAgent(UUID id, String action) {
        if (!props.hierarchyRead()) {
            throw deny(action, "AGENT", id);
        }
        return owned(AGENTS, "/v1/agents/" + id, "AGENT", id, action, r -> mine(r, "parent_agent_id"));
    }

    // ------------------------------------------------------------------ helpers

    private String floatWalletOrNull() {
        String id = agent().path("float_wallet_id").asString(null);
        return id == null || id.isBlank() ? null : id;
    }

    private void throughOutlet(JsonNode row, String field, String action, String type, UUID id) {
        String outletId = row.path(field).asString(null);
        if (outletId == null) {
            throw deny(action, type, id);
        }
        JsonNode outlet;
        try {
            outlet = down.get(AGENTS, "/v1/agent-outlets/" + outletId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw deny(action, type, id);
            }
            throw e;
        }
        if (!mine(outlet, "agent_id")) {
            throw deny(action, type, id);
        }
    }

    private JsonNode owned(String service, String path, String type, UUID id, String action, Predicate<JsonNode> isMine) {
        JsonNode row = fetch(service, path, type, id);
        if (!isMine.test(row)) {
            throw deny(action, type, id);
        }
        return row;
    }

    /** Downstream fetch; a downstream 404 is answered with the same neutral 404. */
    private JsonNode fetch(String service, String path, String type, UUID id) {
        try {
            return down.get(service, path);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw notFound(type, id);
            }
            throw e;
        }
    }

    /** Audits the refusal and returns the 404 to throw (same body as a missing object). */
    public ApiException deny(String action, String type, UUID id) {
        audit.denied(action, type, id, "not_owned");
        return notFound(type, id);
    }

    public static ApiException notFound(String type, UUID id) {
        return ApiException.notFound(type.toLowerCase().replace('_', ' '), id);
    }
}
