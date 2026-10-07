package io.meridian.agentchannel.application;

import static io.meridian.agentchannel.application.Ownership.AGENTS;
import static io.meridian.agentchannel.application.Ownership.me;
import static io.meridian.agentchannel.application.Ownership.mine;

import io.meridian.agentchannel.application.Downstream.Query;
import io.meridian.common.error.ApiException;
import io.meridian.web.security.AgentContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Float requests, cash-in-transit requests and tills of the agent. Bodies are rebuilt server-side: requesting agent,
 * target float wallet, source (parent agent), currency and the till operator never come from the client.
 */
@Service
public class OutletService {
    private static final int MAX_ROWS = 500;

    private final Downstream down;
    private final Ownership own;
    private final StepUp stepUp;
    private final ActionAudit audit;

    public OutletService(Downstream down, Ownership own, StepUp stepUp, ActionAudit audit) {
        this.down = down;
        this.own = own;
        this.stepUp = stepUp;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ float requests

    public JsonNode floatRequests(String status, String clientKey, Integer limit, String cursor) {
        return down.page(down.get(AGENTS, "/v1/agent-float-requests", Query.of("requesting_agent_id", me())
                        .with("status", status)
                        .with("idempotency_key", clientKey == null || clientKey.isBlank() ? null
                                : Downstream.key(clientKey, "float-request"))
                        .paging(limit, cursor)),
                r -> mine(r, "requesting_agent_id"));
    }

    public JsonNode floatRequest(UUID id) {
        return own.floatRequest(id, "agentMeFloatRequestsGet");
    }

    /** TOP_UP (bank transfer / cash deposit with its reference) or REBALANCE from the parent agent. */
    public JsonNode createFloatRequest(JsonNode req, String key) {
        String action = "agentMeFloatRequestsCreate";
        JsonNode agent = own.activeAgent();
        String type = Req.required(req, "request_type");
        String amount = Req.amount(req, "amount");
        ObjectNode body = down.object()
                .put("request_type", type)
                .put("requesting_agent_id", me().toString())
                .put("target_wallet_id", own.floatWallet().toString())
                .put("amount", amount)
                .put("currency", agent.path("currency").asString());
        switch (type) {
            case "TOP_UP" -> {
                String funding = Req.text(req, "funding_method");
                funding = funding == null || funding.isBlank() ? "BANK_TRANSFER" : funding;
                if (!Set.of("BANK_TRANSFER", "CASH_DEPOSIT").contains(funding)) {
                    throw ApiException.unprocessable("invalid_funding_method",
                            "A top-up is funded by BANK_TRANSFER or CASH_DEPOSIT");
                }
                body.put("funding_method", funding).put("bank_reference", Req.required(req, "bank_reference"));
            }
            case "REBALANCE" -> {
                String parent = agent.path("parent_agent_id").asString(null);
                if (parent == null || parent.isBlank()) {
                    throw ApiException.unprocessable("no_parent_agent", "Only agents with a parent agent can rebalance");
                }
                body.put("source_agent_id", parent);
            }
            default -> throw ApiException.unprocessable("request_type_not_allowed",
                    "The agent app can request TOP_UP or REBALANCE");
        }
        stepUp.require(action, "FLOAT_REQUEST", null);
        JsonNode r = down.post(AGENTS, "/v1/agent-float-requests", body, key, "float-request");
        audit.succeeded(action, "FLOAT_REQUEST", null, r.path("id").asString(null));
        return r;
    }

    public JsonNode cancelFloatRequest(UUID id, JsonNode req, String key) {
        String action = "agentMeFloatRequestsCancel";
        own.floatRequest(id, action);
        ObjectNode body = down.object().put("reason", Req.text(req, "reason"));
        JsonNode r = down.post(AGENTS, "/v1/agent-float-requests/" + id + "/cancel", body, key, "float-cancel:" + id);
        audit.succeeded(action, "FLOAT_REQUEST", id, null);
        return r;
    }

    // ------------------------------------------------------------------ cash management requests

    public JsonNode cashRequests(UUID outletId, String status, Integer limit, String cursor) {
        return perOutlet("/v1/cash-management-requests", "agentMeCashRequestsList", outletId, status, limit, cursor);
    }

    public JsonNode cashRequest(UUID id) {
        return own.cashRequest(id, "agentMeCashRequestsGet");
    }

    public JsonNode createCashRequest(JsonNode req, String key) {
        String action = "agentMeCashRequestsCreate";
        JsonNode agent = own.activeAgent();
        UUID outletId = Req.uuid(req, "agent_outlet_id", true);
        own.activeOutlet(outletId, action);
        String type = Req.required(req, "request_type");
        if (!Set.of("CASH_DELIVERY", "CASH_PICKUP").contains(type)) {
            throw ApiException.unprocessable("invalid_request_type", "request_type must be CASH_DELIVERY or CASH_PICKUP");
        }
        ObjectNode body = down.object()
                .put("agent_outlet_id", outletId.toString())
                .put("request_type", type)
                .put("amount", Req.amount(req, "amount"))
                .put("currency", agent.path("currency").asString());
        Req.copy(body, req, "denominations");
        Req.copy(body, req, "requested_for");
        JsonNode r = down.post(AGENTS, "/v1/cash-management-requests", body, key, "cash-request");
        audit.succeeded(action, "CASH_REQUEST", outletId, r.path("id").asString(null));
        return r;
    }

    public JsonNode cancelCashRequest(UUID id, JsonNode req, String key) {
        String action = "agentMeCashRequestsCancel";
        own.cashRequest(id, action);
        ObjectNode body = down.object().put("reason", Req.text(req, "reason"));
        JsonNode r = down.post(AGENTS, "/v1/cash-management-requests/" + id + "/cancel", body, key,
                "cash-request-cancel:" + id);
        audit.succeeded(action, "CASH_REQUEST", id, null);
        return r;
    }

    // ------------------------------------------------------------------ tills

    public JsonNode tills(UUID outletId, String status, Integer limit, String cursor) {
        return perOutlet("/v1/agent-tills", "agentMeTillsList", outletId, status, limit, cursor);
    }

    public JsonNode till(UUID id) {
        return own.till(id, "agentMeTillsGet");
    }

    public JsonNode createTill(JsonNode req, String key) {
        String action = "agentMeTillsCreate";
        JsonNode agent = own.activeAgent();
        UUID outletId = Req.uuid(req, "agent_outlet_id", true);
        own.activeOutlet(outletId, action);
        ObjectNode body = down.object()
                .put("agent_outlet_id", outletId.toString())
                .put("till_number", Req.required(req, "till_number"))
                .put("currency", agent.path("currency").asString());
        JsonNode r = down.post(AGENTS, "/v1/agent-tills", body, key, "till-create");
        audit.succeeded(action, "TILL", outletId, r.path("id").asString(null));
        return r;
    }

    /** Opens a till; the operator is the signed-in user (never a user id from the client). */
    public JsonNode openTill(UUID id, JsonNode req, String key) {
        String action = "agentMeTillsOpen";
        own.activeAgent();
        own.till(id, action);
        ObjectNode body = down.object()
                .put("operator_user_id", AgentContext.require().userId().toString())
                .put("opening_cash", cash(req, "opening_cash"));
        Req.copy(body, req, "denominations");
        JsonNode r = down.post(AGENTS, "/v1/agent-tills/" + id + "/open", body, key, "till-open:" + id);
        audit.succeeded(action, "TILL", id, null);
        return r;
    }

    public JsonNode closeTill(UUID id, JsonNode req, String key) {
        String action = "agentMeTillsClose";
        own.till(id, action);
        ObjectNode body = down.object().put("declared_cash", cash(req, "declared_cash"));
        Req.copy(body, req, "denominations");
        JsonNode r = down.post(AGENTS, "/v1/agent-tills/" + id + "/close", body, key, "till-close:" + id);
        audit.succeeded(action, "TILL", id, null);
        return r;
    }

    // ------------------------------------------------------------------ helpers

    /** Zero or more (a till may open or close with no cash). */
    private static String cash(JsonNode req, String field) {
        java.math.BigDecimal v;
        try {
            v = new java.math.BigDecimal(Req.required(req, field));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_value", field + " must be a decimal number");
        }
        if (v.signum() < 0) {
            throw ApiException.unprocessable("invalid_amount", field + " must not be negative");
        }
        return v.toPlainString();
    }

    /** Rows of one of the agent's outlets (checked), or of all its outlets gathered into one page. */
    private JsonNode perOutlet(String path, String action, UUID outletId, String status, Integer limit, String cursor) {
        if (outletId != null) {
            own.outlet(outletId, action);
            String o = outletId.toString();
            return down.page(down.get(AGENTS, path, Query.of("agent_outlet_id", outletId).with("status", status)
                    .paging(limit, cursor)), r -> o.equals(r.path("agent_outlet_id").asString(null)));
        }
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode outlet : own.outlets()) {
            String o = outlet.path("id").asString();
            down.all(AGENTS, path, Query.of("agent_outlet_id", o).with("status", status), MAX_ROWS).stream()
                    .filter(r -> o.equals(r.path("agent_outlet_id").asString(null))).forEach(rows::add);
        }
        rows.sort(Comparator.comparing((JsonNode r) -> r.path("id").asString()).reversed());
        return down.page(rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows);
    }
}
