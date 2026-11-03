package io.meridian.agentnetwork.application;

import io.meridian.agentnetwork.config.AgentNetworkProperties;
import io.meridian.agentnetwork.domain.Hierarchy;
import io.meridian.agentnetwork.domain.StockRules;
import io.meridian.agentnetwork.infrastructure.Downstream;
import io.meridian.agentnetwork.infrastructure.Rows;
import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-FLOAT-1 (MQ-STK-1/4/14): operator-level e-money stock operations. STOCK_CREATION mints e-money against a physical
 * bank deposit (DEBIT 1150 bank clearing / CREDIT the capital pool wallet IND01); REIMBURSEMENT converts e-money back
 * to cash (DEBIT the entity wallet / CREDIT capital, then DEBIT capital / CREDIT 1150). Maker-checker: a checker other
 * than the maker approves and the movement executes in wallet-service; an unknown (5xx) outcome keeps the operation
 * REQUESTED for a retry with the same, stable transfer keys (mirrors {@link FloatService}).
 */
@Service
public class StockOperationService {
    static final String OP = "stock_operation";
    /** Approval resource type in workflow-service (SEC-03 owner = agent-network-service). */
    public static final String APPROVAL_RESOURCE = "stock_operation";

    private final Rows rows;
    private final Downstream downstream;
    private final Events events;
    private final AgentNetworkProperties props;
    private final TransactionTemplate tx;
    private final Approvals approvals;

    public StockOperationService(Rows rows, Downstream downstream, Events events, AgentNetworkProperties props,
                                 TransactionTemplate tx, Approvals approvals) {
        this.rows = rows;
        this.downstream = downstream;
        this.events = events;
        this.props = props;
        this.tx = tx;
        this.approvals = approvals;
    }

    public ObjectNode create(JsonNode req, String idempotencyKey) {
        return tx.execute(s -> {
            ObjectNode v = rows.columns(OP, req);
            v.remove("idempotency_key");
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                v.put("idempotency_key", idempotencyKey);
            }
            String type = Rows.str(v, "operation_type");
            StockRules.requireInstrument(Rows.str(v, "instrument"));
            StockRules.requireAmount(Rows.dec(v, "amount"));
            StockRules.legs(type); // validates the operation type
            StockRules.requireEntity(type, Rows.uuid(v, "entity_wallet_id"));
            if ("REIMBURSEMENT".equals(type)) {
                UUID entity = Rows.uuid(v, "entity_wallet_id");
                JsonNode w = downstream.wallet(entity);
                if (!"ACTIVE".equals(w.path("status").asString(""))) {
                    throw ApiException.unprocessable("entity_wallet_not_active", "The entity wallet is " + w.path("status").asString(""));
                }
                if (!Rows.str(v, "currency").equals(w.path("currency").asString())) {
                    throw ApiException.unprocessable("currency_mismatch", "The operation must be in the entity wallet currency");
                }
            }
            v.put("status", "REQUESTED");
            ObjectNode row = rows.insert(OP, v);
            requestApproval(row);
            return row;
        });
    }

    private void requestApproval(ObjectNode row) {
        UUID id = Rows.uuid(row, "id");
        BigDecimal amount = Rows.dec(row, "amount");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation_type", Rows.str(row, "operation_type"));
        payload.put("instrument", Rows.str(row, "instrument"));
        payload.put("entity_wallet_id", Rows.str(row, "entity_wallet_id"));
        payload.put("amount", amount.toPlainString());
        payload.put("currency", Rows.str(row, "currency"));
        payload.put("bank_reference", Rows.str(row, "bank_reference"));
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                        "Stock " + Rows.str(row, "operation_type").replace('_', ' ').toLowerCase() + " "
                                + amount.toPlainString() + " " + Rows.str(row, "currency"), payload)
                        .withAmount(amount, Rows.str(row, "currency")),
                "submit:" + id);
    }

    /** True while the operation is REQUESTED for the submitted amount (idempotent-decision guard). */
    public boolean awaitsApproval(UUID id, BigDecimal amount) {
        return rows.find(OP, id)
                .filter(r -> "REQUESTED".equals(Rows.str(r, "status")))
                .filter(r -> amount == null || amount.compareTo(Rows.dec(r, "amount")) == 0)
                .isPresent();
    }

    /**
     * Approves (checker other than the maker) and posts the stock operation legs in wallet-service. A wallet-service
     * rejection leaves the operation FAILED with the reason; an unknown outcome (5xx) keeps it REQUESTED for a retry.
     */
    public ObjectNode approve(UUID id, String comment) {
        ObjectNode r = rows.get(OP, id);
        Hierarchy.requireRequestTransition(Rows.str(r, "status"), "APPROVED");
        if (TenantContext.principal().equals(Rows.str(r, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The operation must be approved by a different user than its maker");
        }
        UUID capital = capital();
        UUID bankClearing = bankClearing();
        String type = Rows.str(r, "operation_type");
        BigDecimal amount = Rows.dec(r, "amount");
        String currency = Rows.str(r, "currency");
        String desc = type.replace('_', ' ').toLowerCase() + " " + id + (comment == null ? "" : ": " + comment);
        JsonNode capitalTxn;
        JsonNode bankTxn = null;
        try {
            if ("STOCK_CREATION".equals(type)) {
                // DEBIT bank clearing (1150) / CREDIT capital (IND01): capital wallet is the credited side.
                capitalTxn = downstream.walletToGl("sto-" + id + "-create", "BANK_TO_WALLET", capital, false,
                        bankClearing, amount, currency, null, desc);
            } else {
                // REIMBURSEMENT: DEBIT entity / CREDIT capital, then DEBIT capital / CREDIT bank clearing.
                capitalTxn = downstream.floatTransfer("sto-" + id + "-reimb-1", Rows.uuid(r, "entity_wallet_id"),
                        capital, amount, currency, null, desc + " (entity->capital)");
                bankTxn = downstream.walletToGl("sto-" + id + "-reimb-2", "WALLET_TO_BANK", capital, true,
                        bankClearing, amount, currency, null, desc + " (capital->bank)");
            }
        } catch (ApiException e) {
            if (e.status() >= 500) {
                throw e; // unknown outcome: keep REQUESTED, retry with the same transfer keys (no double move)
            }
            String reason = truncate(e.code() + ": " + e.getMessage());
            ObjectNode failed = tx.execute(s -> {
                rows.lock(OP, id);
                ObjectNode v = rows.object();
                v.put("status", "FAILED");
                v.put("approved_by", TenantContext.principal());
                v.put("failure_reason", reason);
                v.put("capital_wallet_id", capital.toString());
                v.put("bank_clearing_ledger_account_id", bankClearing.toString());
                rows.update(OP, id, v);
                rows.execute("update stock_operation set approved_at = now() where id = ?", id);
                return rows.get(OP, id);
            });
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
            return failed;
        }
        JsonNode fBank = bankTxn;
        ObjectNode done = tx.execute(s -> {
            ObjectNode cur = rows.lock(OP, id);
            if (!"REQUESTED".equals(Rows.str(cur, "status"))) {
                return cur;
            }
            ObjectNode v = rows.object();
            v.put("status", "COMPLETED");
            v.put("approved_by", TenantContext.principal());
            v.put("capital_wallet_id", capital.toString());
            v.put("bank_clearing_ledger_account_id", bankClearing.toString());
            v.put("wallet_transaction_id", capitalTxn.path("id").asString());
            if (fBank != null) {
                v.put("bank_wallet_transaction_id", fBank.path("id").asString());
            }
            rows.update(OP, id, v);
            rows.execute("update stock_operation set approved_at = now(), completed_at = now() where id = ?", id);
            ObjectNode row = rows.get(OP, id);
            events.publish("wallets.stock_operation.completed", OP, row);
            return row;
        });
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
        return done;
    }

    public ObjectNode close(UUID id, String to, String reason) {
        return tx.execute(s -> {
            ObjectNode r = rows.lock(OP, id);
            Hierarchy.requireRequestTransition(Rows.str(r, "status"), to);
            if ("APPROVED".equals(Rows.str(r, "status"))) {
                throw ApiException.conflict("invalid_transition", "An approved operation is being executed");
            }
            ObjectNode v = rows.object();
            v.put("status", to);
            v.put("rejection_reason", reason == null ? to.toLowerCase() : reason);
            ObjectNode row = rows.update(OP, id, v);
            events.publish("wallets.stock_operation.rejected", OP, row);
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, to.toLowerCase() + " directly");
            return row;
        });
    }

    private UUID capital() {
        UUID c = props.capitalWalletId();
        if (c == null) {
            throw new ApiException(422, "capital_not_configured",
                    "No capital pool wallet is configured for this tenant (config-service ledger/agent-network-service.capital_wallet_id)");
        }
        return c;
    }

    private UUID bankClearing() {
        UUID a = props.gl().bankClearing();
        if (a == null) {
            throw new ApiException(422, "bank_clearing_not_configured",
                    "No bank clearing ledger account is configured for this tenant (config-service ledger/agent-network-service.gl.bank_clearing)");
        }
        return a;
    }

    private static String truncate(String s) {
        return s.length() > 255 ? s.substring(0, 255) : s;
    }
}
