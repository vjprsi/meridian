package io.meridian.casemanagement.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.casemanagement.config.CaseManagementProperties;
import io.meridian.casemanagement.domain.DisputeRules;
import io.meridian.casemanagement.infrastructure.Db;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-OPS-1 (D-ED-13, G-09): the maker-checker wallet transaction-correction workflow for non-card wallet
 * transactions. The maker requests a correction ({@link #request}); a different user (checker) approves it
 * ({@link #approve}), which executes the reversal exactly once through wallet-service's existing reversal path
 * ({@link WalletReversalPort}) and links the resulting REVERSAL transaction id. Reuses the platform approval
 * mechanism ({@link Approvals} + {@link io.meridian.casemanagement.application.CorrectionApprovalHandler}), exactly
 * like {@link CaseService}'s disposition maker-checker.
 *
 * <p>Idempotent: each correction carries a deterministic {@code idempotency_key}; execution re-checks the status and
 * the existing reversal id under a row lock ({@link DisputeRules#canExecute}) so a retry or a re-delivered approval
 * never posts the reversal twice.
 */
@Service
public class CorrectionService {
    /** Approval resource type for a wallet transaction correction (routes the decision to CorrectionApprovalHandler). */
    public static final String CORRECTION_APPROVAL = "wallet_txn_correction";
    static final String TABLE = "wallet_txn_correction";

    private final Db db;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final Approvals approvals;
    private final WalletReversalPort wallet;
    private final CaseManagementProperties props;

    public CorrectionService(Db db, CrudEngine crud, OutboxWriter outbox, JsonMapper json, Approvals approvals,
                             WalletReversalPort wallet, CaseManagementProperties props) {
        this.db = db;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
        this.approvals = approvals;
        this.wallet = wallet;
        this.props = props;
    }

    private void gate() {
        if (!props.dispute().enabled()) {
            throw ApiException.unprocessable("wallet_corrections_disabled",
                    "The wallet dispute / correction workflow is not enabled for this tenant");
        }
    }

    // ------------------------------------------------------------------ request (maker)

    /** Maker requests a correction of a specific wallet transaction; it waits for a different user's approval. */
    @Transactional
    public String request(JsonNode body) {
        gate();
        return insert(crud.toColumns(TABLE, body));
    }

    /** Request raised by a dispute resolution; linked to its originating dispute. */
    @Transactional
    public JsonNode requestForDispute(UUID disputeId, UUID walletTransactionId, String reason, BigDecimal amount,
                                      String currency, String rationale) {
        ObjectNode v = json.createObjectNode();
        v.put("wallet_dispute_id", disputeId.toString());
        v.put("wallet_transaction_id", walletTransactionId.toString());
        v.put("reason", reason);
        if (amount != null) {
            v.put("amount", amount.toPlainString());
        }
        if (currency != null) {
            v.put("currency", currency);
        }
        v.put("rationale", rationale == null ? "Correction from dispute resolution" : rationale);
        return json.readTree(insert(v));
    }

    private String insert(ObjectNode v) {
        String reason = Db.str(v, "reason");
        if (!DisputeRules.validReason(reason)) {
            throw ApiException.unprocessable("invalid_reason", "reason must be one of " + DisputeRules.REASONS);
        }
        if (Db.str(v, "wallet_transaction_id") == null) {
            throw ApiException.unprocessable("wallet_transaction_id_required", "wallet_transaction_id is required");
        }
        if (Db.str(v, "rationale") == null) {
            throw ApiException.unprocessable("rationale_required", "rationale is required");
        }
        v.remove("status");
        v.remove("reversal_transaction_id");
        v.remove("executed_at");
        v.put("status", "PENDING_APPROVAL");
        v.put("requested_by", TenantContext.principal());
        v.put("idempotency_key", "wtc-reverse:" + UUID.randomUUID());
        String row = crud.insert(TABLE, v);
        JsonNode n = json.readTree(row);
        outbox.publish("risk.wallet_txn_correction.requested", TABLE, id(n), n);
        approvals.request(approvalSpec(n), "correction:" + id(n));
        return row;
    }

    private ApprovalSpec approvalSpec(JsonNode c) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("wallet_txn_correction_id", c.get("id").asString());
        payload.put("wallet_transaction_id", c.get("wallet_transaction_id").asString());
        payload.put("reason", c.get("reason").asString());
        payload.put("requested_by", c.get("requested_by").asString());
        if (c.hasNonNull("wallet_dispute_id")) {
            payload.put("wallet_dispute_id", c.get("wallet_dispute_id").asString());
        }
        ApprovalSpec spec = ApprovalSpec.of(CORRECTION_APPROVAL, id(c), "approve",
                        "Reverse wallet transaction " + c.get("wallet_transaction_id").asString()
                                + " (" + c.get("reason").asString() + ")", payload)
                .withSummary("Approve a maker-checker correction that reverses a wallet transaction");
        BigDecimal amount = Db.dec(c, "amount");
        return amount != null && Db.str(c, "currency") != null ? spec.withAmount(amount, Db.str(c, "currency")) : spec;
    }

    // ------------------------------------------------------------------ checker

    /** True while the correction still awaits a checker (REQUESTED or PENDING_APPROVAL). */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id) {
        return db.one("select row_to_json(c)::text from " + TABLE + " c where id = ?", id)
                .map(c -> DisputeRules.correctionAllows("approve", Db.str(c, "status")))
                .orElse(false);
    }

    /** Checker approves (a different user from the maker) and the reversal executes once. */
    @Transactional
    public String approve(UUID id, String comment) {
        JsonNode c = pending(id);
        String maker = c.get("requested_by").asString();
        if (!DisputeRules.distinctChecker(maker, TenantContext.principal())) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The maker who requested the correction cannot approve it");
        }
        db.update("update " + TABLE + " set status = 'APPROVED', approved_by = ?, approval_comment = ?, updated_by = ? where id = ?",
                TenantContext.principal(), comment, TenantContext.principal(), id);
        JsonNode approved = db.require(TABLE, id, false);
        outbox.publish("risk.wallet_txn_correction.approved", TABLE, id, approved);
        approvals.cancelOpen(CORRECTION_APPROVAL, id, null, "approved directly");
        return execute(id);
    }

    @Transactional
    public String reject(UUID id, String reason) {
        JsonNode c = pending(id);
        db.update("update " + TABLE + " set status = 'REJECTED', approved_by = ?, approval_comment = ?, updated_by = ? where id = ?",
                TenantContext.principal(), reason, TenantContext.principal(), id);
        JsonNode row = db.require(TABLE, id, false);
        outbox.publish("risk.wallet_txn_correction.rejected", TABLE, id, row);
        approvals.cancelOpen(CORRECTION_APPROVAL, id, null, "rejected directly");
        return db.text(row);
    }

    /**
     * Executes the reversal through wallet-service exactly once (idempotent; terminal-state guarded). Called straight
     * after a direct approval and reusable by any retry path. A correction that already carries a reversal id, or that
     * is not APPROVED/FAILED, is a no-op.
     */
    @Transactional
    public String execute(UUID id) {
        JsonNode c = db.require(TABLE, id, true);
        if (!DisputeRules.canExecute(Db.str(c, "status"), Db.str(c, "reversal_transaction_id"))) {
            return db.text(c); // already executed, rejected or still awaiting approval: nothing to do
        }
        UUID txnId = UUID.fromString(c.get("wallet_transaction_id").asString());
        BigDecimal amount = Db.dec(c, "amount");
        String currency = Db.str(c, "currency");
        String reason = "Correction " + c.get("reason").asString() + ": " + Db.str(c, "rationale");
        UUID reversalId = wallet.reverse(txnId, amount, currency, reason, c.get("idempotency_key").asString());
        db.update("update " + TABLE + " set status = 'EXECUTED', reversal_transaction_id = ?, executed_at = now(), updated_by = ? where id = ?",
                reversalId, TenantContext.principal(), id);
        JsonNode row = db.require(TABLE, id, false);
        outbox.publish("risk.wallet_txn_correction.executed", TABLE, id, row);
        return db.text(row);
    }

    private JsonNode pending(UUID id) {
        JsonNode c = db.require(TABLE, id, true);
        if (!DisputeRules.correctionAllows("approve", Db.str(c, "status"))) {
            throw ApiException.conflict("invalid_transition", "Correction is already " + Db.str(c, "status"));
        }
        return c;
    }

    private static UUID id(JsonNode n) {
        return UUID.fromString(n.get("id").asString());
    }
}
