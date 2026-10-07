package io.meridian.agentnetwork.application;

import io.meridian.agentnetwork.config.AgentNetworkProperties;
import io.meridian.agentnetwork.domain.Hierarchy;
import io.meridian.agentnetwork.infrastructure.Downstream;
import io.meridian.agentnetwork.infrastructure.Rows;
import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Float positions and float requests. Top-ups come from the platform treasury wallet, rebalances and transfers from
 * another float wallet of the same super-agent tree, withdrawals go back to the treasury. A checker other than the
 * maker approves; the movement is a FLOAT_TRANSFER executed by wallet-service (and posted to the ledger there).
 */
@Service
public class FloatService {
    static final String FLOAT = "agent_float";
    static final String REQUEST = "agent_float_request";
    /** Approval resource type for float requests in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "agent_float_request";

    private final Rows rows;
    private final Downstream downstream;
    private final Events events;
    private final AgentNetworkProperties props;
    private final TransactionTemplate tx;
    private final Approvals approvals;

    public FloatService(Rows rows, Downstream downstream, Events events, AgentNetworkProperties props, TransactionTemplate tx,
                        Approvals approvals) {
        this.approvals = approvals;
        this.rows = rows;
        this.downstream = downstream;
        this.events = events;
        this.props = props;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ float positions

    public ObjectNode createFloat(JsonNode req) {
        return tx.execute(s -> {
            ObjectNode v = rows.columns(FLOAT, req);
            ObjectNode agent = rows.find("agent", Rows.uuid(v, "agent_id")).orElseThrow(() ->
                    ApiException.unprocessable("agent_not_found", "The agent does not exist"));
            if (!Rows.str(agent, "currency").equals(Rows.str(v, "currency"))) {
                throw ApiException.unprocessable("currency_mismatch", "The float must be in the agent currency");
            }
            UUID outlet = Rows.uuid(v, "agent_outlet_id");
            if (outlet != null) {
                ObjectNode o = rows.find("agent_outlet", outlet).orElseThrow(() ->
                        ApiException.unprocessable("outlet_not_found", "The outlet does not exist"));
                if (!Rows.str(o, "agent_id").equals(Rows.str(agent, "id"))) {
                    throw ApiException.unprocessable("outlet_mismatch", "The outlet belongs to another agent");
                }
            }
            if (rows.scalar("select count(*) from agent_float where wallet_id = ?", Long.class, Rows.uuid(v, "wallet_id"))
                    .orElse(0L) > 0) {
                throw ApiException.conflict("float_exists", "A float position already exists for this wallet");
            }
            validateThresholds(v);
            return rows.insert(FLOAT, v);
        });
    }

    public static void validateThresholds(JsonNode f) {
        BigDecimal min = Rows.dec(f, "min_balance");
        BigDecimal max = Rows.dec(f, "max_balance");
        BigDecimal alert = Rows.dec(f, "low_float_alert_threshold");
        for (BigDecimal b : new BigDecimal[] {min, max, alert, Rows.dec(f, "rebalance_amount")}) {
            if (b != null && b.signum() < 0) {
                throw ApiException.unprocessable("invalid_threshold", "Float thresholds must not be negative");
            }
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw ApiException.unprocessable("invalid_threshold", "min_balance must be <= max_balance");
        }
        if (alert != null && max != null && alert.compareTo(max) > 0) {
            throw ApiException.unprocessable("invalid_threshold", "low_float_alert_threshold must be <= max_balance");
        }
    }

    /**
     * Reads the float balance, raises the low-float alert (at most once per throttle window, database clock) and, with
     * auto_rebalance, requests a rebalance from the configured source (default: the parent agent) unless one is pending.
     */
    public ObjectNode check(UUID id) {
        ObjectNode f = rows.get(FLOAT, id);
        BigDecimal balance = downstream.balance(Rows.uuid(f, "wallet_id"));
        BigDecimal threshold = Rows.dec(f, "low_float_alert_threshold");
        BigDecimal min = Rows.dec(f, "min_balance");
        boolean low = threshold != null && balance.compareTo(threshold) < 0;
        String status = min != null && balance.compareTo(min) < 0 ? "BELOW_MINIMUM" : low ? "LOW" : "OK";
        return tx.execute(s -> {
            rows.lock(FLOAT, id);
            rows.execute("update agent_float set last_balance = ?, last_checked_at = now() where id = ?", balance, id);
            boolean alert = false;
            UUID rebalance = null;
            if (low) {
                alert = rows.execute("""
                        update agent_float set last_alert_at = now() where id = ?
                          and (last_alert_at is null or last_alert_at < now() - make_interval(mins => ?))""",
                        id, props.lowFloatAlertMinutes()) > 0;
                ObjectNode cur = rows.get(FLOAT, id);
                if (alert) {
                    events.publish("wallets.agent_float.low_balance", FLOAT, cur);
                }
                if (Rows.bool(cur, "auto_rebalance")) {
                    rebalance = autoRebalance(cur, balance);
                }
            }
            ObjectNode out = rows.object();
            out.put("agent_float_id", id.toString());
            out.put("wallet_id", Rows.str(f, "wallet_id"));
            out.put("currency", Rows.str(f, "currency"));
            out.put("balance", balance.toPlainString());
            out.put("status", status);
            out.put("alert_raised", alert);
            out.put("rebalance_request_id", rebalance == null ? null : rebalance.toString());
            return out;
        });
    }

    private UUID autoRebalance(ObjectNode f, BigDecimal balance) {
        UUID wallet = Rows.uuid(f, "wallet_id");
        if (rows.scalar("""
                select count(*) from agent_float_request where target_wallet_id = ? and status in ('REQUESTED', 'APPROVED')""",
                Long.class, wallet).orElse(0L) > 0) {
            return null;
        }
        ObjectNode agent = rows.get("agent", Rows.uuid(f, "agent_id"));
        UUID source = Rows.uuid(f, "rebalance_source_agent_id");
        if (source == null) {
            source = Rows.uuid(agent, "parent_agent_id");
        }
        if (source == null) {
            return null;
        }
        ObjectNode src = rows.get("agent", source);
        if (Rows.uuid(src, "float_wallet_id") == null) {
            return null;
        }
        BigDecimal amount = Rows.dec(f, "rebalance_amount");
        if (amount == null) {
            BigDecimal target = Rows.dec(f, "max_balance") != null ? Rows.dec(f, "max_balance")
                    : Rows.dec(f, "low_float_alert_threshold").multiply(BigDecimal.TWO);
            amount = target.subtract(balance);
        }
        if (amount.signum() <= 0) {
            return null;
        }
        ObjectNode r = rows.object();
        r.put("request_type", "REBALANCE");
        r.put("requesting_agent_id", Rows.str(agent, "id"));
        r.put("source_agent_id", source.toString());
        r.put("source_wallet_id", Rows.str(src, "float_wallet_id"));
        r.put("target_wallet_id", wallet.toString());
        r.put("amount", amount.toPlainString());
        r.put("currency", Rows.str(f, "currency"));
        r.put("funding_method", "SUPER_AGENT");
        r.put("status", "REQUESTED");
        ObjectNode row = rows.insert(REQUEST, r);
        requestApproval(row);
        return Rows.uuid(row, "id");
    }

    // ------------------------------------------------------------------ float requests

    public ObjectNode createRequest(JsonNode req) {
        return tx.execute(s -> {
            ObjectNode v = rows.columns(REQUEST, req);
            String type = Rows.str(v, "request_type");
            BigDecimal amount = Rows.dec(v, "amount");
            if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
                throw ApiException.unprocessable("invalid_amount", "amount must be positive with at most 2 decimals");
            }
            ObjectNode agent = rows.find("agent", Rows.uuid(v, "requesting_agent_id")).orElseThrow(() ->
                    ApiException.unprocessable("agent_not_found", "The requesting agent does not exist"));
            if (!"ACTIVE".equals(Rows.str(agent, "status"))) {
                throw ApiException.unprocessable("agent_not_active", "The requesting agent is " + Rows.str(agent, "status"));
            }
            if (!Rows.str(agent, "currency").equals(Rows.str(v, "currency"))) {
                throw ApiException.unprocessable("currency_mismatch", "Float requests are in the agent currency");
            }
            UUID target = Rows.uuid(v, "target_wallet_id");
            switch (type) {
                case "TOP_UP" -> {
                    String funding = Rows.str(v, "funding_method") == null ? "BANK_TRANSFER" : Rows.str(v, "funding_method");
                    if (!Set.of("BANK_TRANSFER", "CASH_DEPOSIT").contains(funding)) {
                        throw ApiException.unprocessable("invalid_funding_method", "A top-up is funded by BANK_TRANSFER or CASH_DEPOSIT");
                    }
                    if (Rows.str(v, "bank_reference") == null && Rows.str(v, "proof_document_id") == null) {
                        throw ApiException.unprocessable("funding_proof_required", "Give the bank_reference or the deposit slip");
                    }
                    v.put("funding_method", funding);
                    v.put("source_wallet_id", treasury().toString());
                    requireOwnFloat(agent, target);
                }
                case "REBALANCE", "TRANSFER" -> {
                    UUID sourceAgentId = Rows.uuid(v, "source_agent_id") != null ? Rows.uuid(v, "source_agent_id")
                            : Rows.uuid(agent, "parent_agent_id");
                    if (sourceAgentId == null) {
                        throw ApiException.unprocessable("source_agent_required", "A " + type.toLowerCase() + " needs source_agent_id");
                    }
                    ObjectNode source = rows.find("agent", sourceAgentId).orElseThrow(() ->
                            ApiException.unprocessable("source_agent_not_found", "The source agent does not exist"));
                    if (!"ACTIVE".equals(Rows.str(source, "status"))) {
                        throw ApiException.unprocessable("source_agent_not_active", "The source agent is " + Rows.str(source, "status"));
                    }
                    if (!Hierarchy.root(sourceAgentId, Rows.uuid(source, "super_agent_id"))
                            .equals(Hierarchy.root(Rows.uuid(agent, "id"), Rows.uuid(agent, "super_agent_id")))) {
                        throw ApiException.unprocessable("different_hierarchy",
                                "Float can only move within the same super-agent network");
                    }
                    if (Rows.uuid(source, "float_wallet_id") == null) {
                        throw ApiException.unprocessable("source_has_no_float", "The source agent has no float wallet");
                    }
                    v.put("source_agent_id", sourceAgentId.toString());
                    v.put("source_wallet_id", Rows.str(source, "float_wallet_id"));
                    v.put("funding_method", "SUPER_AGENT".equals(Rows.str(source, "agent_level")) ? "SUPER_AGENT" : "WALLET");
                    requireOwnFloat(agent, target);
                }
                case "WITHDRAW" -> {
                    if (!treasury().equals(target)) {
                        throw ApiException.unprocessable("invalid_target_wallet", "A withdrawal goes to the treasury wallet");
                    }
                    v.put("source_wallet_id", Rows.str(agent, "float_wallet_id"));
                    v.put("funding_method", "WALLET");
                }
                default -> throw ApiException.unprocessable("invalid_request_type", "Unknown request type " + type);
            }
            if (Rows.str(v, "source_wallet_id").equals(String.valueOf(target))) {
                throw ApiException.unprocessable("same_wallet", "Source and target wallets must differ");
            }
            if (!"WITHDRAW".equals(type) && agent.hasNonNull("max_float_amount")) {
                BigDecimal balance = downstream.postedBalance(target);
                if (balance.add(amount).compareTo(Rows.dec(agent, "max_float_amount")) > 0) {
                    throw ApiException.unprocessable("max_float_exceeded", "The float would exceed the agent maximum "
                            + Rows.str(agent, "max_float_amount"));
                }
            }
            v.put("status", "REQUESTED");
            ObjectNode row = rows.insert(REQUEST, v);
            requestApproval(row);
            return row;
        });
    }

    /**
     * Central maker-checker: opens the approval in workflow-service (inside the creating transaction); the checker
     * decides in the inbox and {@code FloatRequestApprovalHandler} applies the decision. Amount is the fingerprint.
     */
    private void requestApproval(ObjectNode row) {
        UUID id = Rows.uuid(row, "id");
        BigDecimal amount = Rows.dec(row, "amount");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request_type", Rows.str(row, "request_type"));
        payload.put("requesting_agent_id", Rows.str(row, "requesting_agent_id"));
        payload.put("source_agent_id", Rows.str(row, "source_agent_id"));
        payload.put("source_wallet_id", Rows.str(row, "source_wallet_id"));
        payload.put("target_wallet_id", Rows.str(row, "target_wallet_id"));
        payload.put("amount", amount.toPlainString());
        payload.put("currency", Rows.str(row, "currency"));
        payload.put("funding_method", Rows.str(row, "funding_method"));
        payload.put("bank_reference", Rows.str(row, "bank_reference"));
        payload.put("proof_document_id", Rows.str(row, "proof_document_id"));
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                        "Float " + Rows.str(row, "request_type").replace('_', ' ').toLowerCase() + " " + amount.toPlainString()
                                + " " + Rows.str(row, "currency"), payload)
                        .withAmount(amount, Rows.str(row, "currency")),
                "submit:" + id);
    }

    /** True while the request is REQUESTED for the submitted amount. */
    public boolean awaitsApproval(UUID id, BigDecimal amount) {
        return rows.find(REQUEST, id)
                .filter(r -> "REQUESTED".equals(Rows.str(r, "status")))
                .filter(r -> amount == null || amount.compareTo(Rows.dec(r, "amount")) == 0)
                .isPresent();
    }

    private void requireOwnFloat(ObjectNode agent, UUID target) {
        boolean own = target.toString().equals(Rows.str(agent, "float_wallet_id"))
                || rows.scalar("select count(*) from agent_float where agent_id = ? and wallet_id = ?", Long.class,
                Rows.uuid(agent, "id"), target).orElse(0L) > 0;
        if (!own) {
            throw ApiException.unprocessable("invalid_target_wallet", "The target must be a float wallet of the requesting agent");
        }
    }

    private UUID treasury() {
        if (props.treasuryWalletId() == null) {
            throw new ApiException(422, "treasury_not_configured", "meridian.agent-network.treasury-wallet-id is not configured");
        }
        return props.treasuryWalletId();
    }

    /**
     * Approves (checker other than the maker) and executes the movement in wallet-service. A wallet-service rejection
     * leaves the request FAILED with the reason; an unknown outcome (5xx) keeps it REQUESTED for a retry with the same key.
     */
    public ObjectNode approve(UUID id, String comment) {
        ObjectNode r = rows.get(REQUEST, id);
        Hierarchy.requireRequestTransition(Rows.str(r, "status"), "APPROVED");
        if (TenantContext.principal().equals(Rows.str(r, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The request must be approved by a different user than its maker");
        }
        String description = Rows.str(r, "request_type").replace('_', ' ').toLowerCase() + " float " + Rows.str(r, "id")
                + (comment == null ? "" : ": " + comment);
        JsonNode txn;
        try {
            txn = downstream.floatTransfer("afr-" + id + "-transfer", Rows.uuid(r, "source_wallet_id"),
                    Rows.uuid(r, "target_wallet_id"), Rows.dec(r, "amount"), Rows.str(r, "currency"),
                    Rows.uuid(r, "requesting_agent_id"), description);
        } catch (ApiException e) {
            if (e.status() >= 500) {
                throw e;
            }
            ObjectNode failed = tx.execute(s -> {
                rows.lock(REQUEST, id);
                ObjectNode v = rows.object();
                v.put("status", "FAILED");
                v.put("approved_by", TenantContext.principal());
                v.put("failure_reason", (e.code() + ": " + e.getMessage()).substring(0,
                        Math.min(255, (e.code() + ": " + e.getMessage()).length())));
                rows.update(REQUEST, id, v);
                rows.execute("update agent_float_request set approved_at = now() where id = ?", id);
                return rows.get(REQUEST, id);
            });
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
            return failed;
        }
        ObjectNode done = tx.execute(s -> {
            ObjectNode cur = rows.lock(REQUEST, id);
            if (!"REQUESTED".equals(Rows.str(cur, "status"))) {
                return cur;
            }
            ObjectNode v = rows.object();
            v.put("status", "COMPLETED");
            v.put("approved_by", TenantContext.principal());
            v.put("wallet_transaction_id", txn.path("id").asString());
            rows.update(REQUEST, id, v);
            rows.execute("update agent_float_request set approved_at = now(), completed_at = now() where id = ?", id);
            ObjectNode row = rows.get(REQUEST, id);
            events.publish("wallets.agent_float_request.approved", REQUEST, row);
            return row;
        });
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
        return done;
    }

    public ObjectNode close(UUID id, String to, String reason) {
        return tx.execute(s -> {
            ObjectNode r = rows.lock(REQUEST, id);
            Hierarchy.requireRequestTransition(Rows.str(r, "status"), to);
            if ("APPROVED".equals(Rows.str(r, "status"))) {
                throw ApiException.conflict("invalid_transition", "An approved request is being executed");
            }
            ObjectNode v = rows.object();
            v.put("status", to);
            v.put("rejection_reason", reason == null ? to.toLowerCase() : reason);
            ObjectNode row = rows.update(REQUEST, id, v);
            events.publish("wallets.agent_float_request.rejected", REQUEST, row);
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, to.toLowerCase() + " directly");
            return row;
        });
    }
}
