package io.meridian.merchant.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.merchant.config.MerchantProperties;
import io.meridian.merchant.infrastructure.Rows;
import io.meridian.merchant.infrastructure.WalletClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Merchant refunds: REQUESTED against a completed merchant payment (cumulative refunds never exceed the payment),
 * APPROVED by a checker and executed as a REFUND wallet transaction from the merchant's collection wallet back to the
 * original payer (plus the plan's refund fee), or REJECTED. A wallet rejection marks the refund FAILED.
 *
 * <p>Requested by bank staff: a bank checker decides (central approval request opened on create). Requested in the
 * merchant app (merchant-channel-service relays {@code merchant-user:<id>}): another merchant app user approves first
 * (the merchant's four-eyes); when the bank-review policy applies ({@code meridian.merchant.refund-bank-review}: above
 * the threshold, or a listed merchant risk rating) the refund then moves to PENDING_BANK_APPROVAL and a request opens
 * in the bank's central approval inbox, which only bank checkers decide; otherwise it executes at once.
 */
@Service
public class RefundService {
    static final String TABLE = "merchant_refund";

    private final Rows rows;
    private final WalletClient wallets;
    private final Events events;
    private final TransactionTemplate tx;
    private final MerchantProperties props;
    private final Approvals approvals;

    /** Approval resource type for merchant refunds in workflow-service (G11); the action is {@code approve}. */
    public static final String APPROVAL_RESOURCE = "merchant_refund";
    /** Statuses waiting for a decision. */
    static final java.util.Set<String> PENDING = java.util.Set.of("REQUESTED", "PENDING_BANK_APPROVAL");

    public RefundService(Rows rows, WalletClient wallets, Events events, TransactionTemplate tx, MerchantProperties props,
                         Approvals approvals) {
        this.approvals = approvals;
        this.rows = rows;
        this.wallets = wallets;
        this.events = events;
        this.tx = tx;
        this.props = props;
    }

    public ObjectNode create(JsonNode req, String key) {
        ObjectNode v = rows.columns(TABLE, req);
        UUID merchantId = Rows.uuid(v, "merchant_id");
        ObjectNode m = rows.find("merchant", merchantId).orElseThrow(() ->
                ApiException.unprocessable("merchant_not_found", "The merchant does not exist"));
        if ("TERMINATED".equals(Rows.str(m, "status"))) {
            throw ApiException.unprocessable("merchant_terminated", "The merchant is terminated");
        }
        BigDecimal amount = Rows.dec(v, "amount");
        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw ApiException.unprocessable("invalid_amount", "amount must be positive with at most 2 decimals");
        }
        UUID original = Rows.uuid(v, "original_transaction_id");
        JsonNode o;
        try {
            o = wallets.transaction(original);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("original_transaction_not_found", "The payment does not exist");
            }
            throw e;
        }
        if (!"MERCHANT_PAYMENT".equals(o.path("txn_type").asString())
                || !merchantId.toString().equals(o.path("merchant_id").asString(null))) {
            throw ApiException.unprocessable("not_a_merchant_payment", "The transaction is not a payment to this merchant");
        }
        if (!"COMPLETED".equals(o.path("status").asString())) {
            throw ApiException.unprocessable("original_not_completed", "Only a completed payment can be refunded");
        }
        if (!Rows.str(v, "currency").equals(o.path("currency").asString())) {
            throw ApiException.unprocessable("currency_mismatch", "The payment was in " + o.path("currency").asString());
        }
        BigDecimal paid = new BigDecimal(o.get("amount").asString());
        return tx.execute(s -> {
            rows.lock("merchant", merchantId);
            BigDecimal already = rows.scalar("""
                    select coalesce(sum(amount), 0) from merchant_refund
                    where original_transaction_id = ? and status in ('REQUESTED', 'PENDING_BANK_APPROVAL', 'APPROVED', 'COMPLETED')""",
                    BigDecimal.class, original).orElse(BigDecimal.ZERO);
            if (already.add(amount).compareTo(paid) > 0) {
                throw ApiException.unprocessable("refund_exceeds_amount", "Refunds would exceed the payment of "
                        + paid.toPlainString() + " (" + already.toPlainString() + " already refunded or pending)");
            }
            v.put("status", "REQUESTED");
            v.put("idempotency_key", key);
            ObjectNode row = rows.insert(TABLE, v);
            // Requested in the merchant app: the merchant's own four-eyes comes first (another app user approves);
            // the bank's checkers see it afterwards only when the policy requires it (approve -> PENDING_BANK_APPROVAL).
            if (!merchantUser(TenantContext.principal())) {
                // Central maker-checker: the bank's checker decides in the workflow inbox; RefundApprovalHandler applies it.
                openCentralRequest(row, m, o, paid, already, "approve:" + Rows.uuid(row, "id"), null);
            }
            return row;
        });
    }

    /** Opens the bank-side approval request of a refund (workflow inbox); the payload carries what a checker needs. */
    private void openCentralRequest(ObjectNode row, ObjectNode m, JsonNode original, BigDecimal paid, BigDecimal already,
                                    String key, String bankReviewReason) {
        UUID id = Rows.uuid(row, "id");
        BigDecimal amt = Rows.dec(row, "amount").setScale(2, RoundingMode.UNNECESSARY);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchant_id", Rows.str(m, "id"));
        payload.put("merchant_code", Rows.str(m, "merchant_code"));
        payload.put("trading_name", Rows.str(m, "trading_name"));
        payload.put("merchant_risk_rating", Rows.str(m, "risk_rating"));
        payload.put("original_transaction_id", Rows.str(row, "original_transaction_id"));
        payload.put("original_reference", original == null ? null : original.path("reference").asString(null));
        payload.put("original_amount", paid == null ? null : paid.toPlainString());
        payload.put("already_refunded_or_pending", already == null ? null : already.toPlainString());
        payload.put("amount", amt.toPlainString());
        payload.put("currency", Rows.str(row, "currency"));
        payload.put("reason", Rows.str(row, "reason"));
        if (bankReviewReason != null) {
            payload.put("bank_review_reason", bankReviewReason);
            payload.put("merchant_requested_by", Rows.str(row, "created_by"));
            payload.put("merchant_approved_by", TenantContext.principal());
        }
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                        "Refund " + amt.toPlainString() + " " + Rows.str(row, "currency") + " by merchant "
                                + Rows.str(m, "trading_name") + (bankReviewReason == null ? "" : " (merchant-approved)"),
                        payload)
                .withAmount(amt, Rows.str(row, "currency")), key);
    }

    /** A merchant app user relayed by merchant-channel-service ({@code merchant-user:<id>}), not bank staff. */
    static boolean merchantUser(String principal) {
        return principal != null && principal.startsWith("merchant-user:");
    }

    /**
     * True while the refund waits for a decision (REQUESTED, or PENDING_BANK_APPROVAL after the merchant's own approval)
     * for the submitted amount and payment (the submission's fingerprint).
     */
    public boolean awaitsApproval(UUID id, String amount, String originalTransactionId) {
        return rows.find(TABLE, id)
                .filter(r -> PENDING.contains(Rows.str(r, "status")))
                .filter(r -> amount == null || new BigDecimal(amount).compareTo(Rows.dec(r, "amount")) == 0)
                .filter(r -> originalTransactionId == null
                        || originalTransactionId.equals(Rows.str(r, "original_transaction_id")))
                .isPresent();
    }

    /**
     * Approves a refund. Bank staff (or a central approval) execute it. A merchant app user (the merchant's own
     * four-eyes, relayed by merchant-channel-service) executes it only when the bank-review policy does not apply;
     * otherwise the refund moves to PENDING_BANK_APPROVAL and a request opens in the bank's central approval inbox,
     * where a bank checker (never a merchant user) decides.
     */
    public ObjectNode approve(UUID id, String comment) {
        ObjectNode r = rows.get(TABLE, id);
        String status = Rows.str(r, "status");
        if (!PENDING.contains(status)) {
            throw ApiException.conflict("invalid_transition", "The refund is " + status);
        }
        String principal = TenantContext.principal();
        if (merchantUser(principal) && "PENDING_BANK_APPROVAL".equals(status)) {
            // also what a retried merchant approval sees: the merchant's part is done
            throw ApiException.conflict("awaiting_bank_approval",
                    "The refund was approved by the merchant and waits for the bank's checkers");
        }
        if (principal.equals(Rows.str(r, "created_by")) || principal.equals(Rows.str(r, "merchant_approved_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "A refund must be approved by a different user than its requester");
        }
        ObjectNode m = rows.get("merchant", Rows.uuid(r, "merchant_id"));
        if (merchantUser(principal)) {
            String reason = props.refundBankReview().reason(Rows.dec(r, "amount"), Rows.str(r, "currency"),
                    Rows.str(m, "risk_rating"));
            if (reason != null) {
                return toBankReview(id, m, reason);
            }
        }
        return execute(id, r, m);
    }

    /** The merchant approved it; the bank's checkers decide next (central approval inbox). */
    private ObjectNode toBankReview(UUID id, ObjectNode m, String reason) {
        return tx.execute(s -> {
            ObjectNode locked = rows.lock(TABLE, id);
            if (!"REQUESTED".equals(Rows.str(locked, "status"))) {
                throw ApiException.conflict("invalid_transition", "The refund is " + Rows.str(locked, "status"));
            }
            ObjectNode v = rows.object();
            v.put("status", "PENDING_BANK_APPROVAL");
            v.put("merchant_approved_by", TenantContext.principal());
            v.put("bank_review_reason", reason);
            rows.update(TABLE, id, v);
            rows.execute("update merchant_refund set merchant_approved_at = now() where id = ?", id);
            ObjectNode after = rows.get(TABLE, id);
            openCentralRequest(after, m, null, null, null, "bank-approve:" + id, reason);
            events.publish("wallets.merchant_refund.pending_bank_approval", TABLE, after);
            return after;
        });
    }

    private ObjectNode execute(UUID id, ObjectNode r, ObjectNode m) {
        JsonNode original = wallets.transaction(Rows.uuid(r, "original_transaction_id"));
        BigDecimal amount = Rows.dec(r, "amount");
        String currency = Rows.str(r, "currency");
        String txnId;
        try {
            ObjectNode b = wallets.txn("REFUND", "SYSTEM", "MERCHANT", amount, currency);
            b.put("payer_wallet_id", Rows.str(m, "settlement_wallet_id"));
            b.put("payee_wallet_id", original.get("payer_wallet_id").asString());
            b.put("original_transaction_id", Rows.str(r, "original_transaction_id"));
            b.put("merchant_id", Rows.str(m, "id"));
            b.put("description", "Refund from " + Rows.str(m, "trading_name") + ": " + Rows.str(r, "reason"));
            txnId = wallets.transact(b, "mrf-" + id + "-refund").get("id").asString();
            BigDecimal refundFee = refundFee(m);
            if (refundFee.signum() > 0 && props.feeIncomeAccountId() != null) {
                ObjectNode fee = wallets.txn("FEE", "SYSTEM", "SYSTEM", refundFee, currency);
                fee.put("payer_wallet_id", Rows.str(m, "settlement_wallet_id"));
                fee.put("counterparty_ledger_account_id", props.feeIncomeAccountId().toString());
                fee.put("merchant_id", Rows.str(m, "id"));
                fee.put("description", "Refund fee " + id);
                wallets.transact(fee, "mrf-" + id + "-fee");
            }
        } catch (ApiException e) {
            if (e.status() >= 500) {
                throw e;
            }
            tx.executeWithoutResult(s -> {
                rows.lock(TABLE, id);
                ObjectNode v = rows.object();
                v.put("status", "FAILED");
                v.put("approved_by", TenantContext.principal());
                String msg = e.code() + ": " + e.getMessage();
                v.put("failure_reason", msg.length() > 255 ? msg.substring(0, 255) : msg);
                rows.update(TABLE, id, v);
                approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "refund failed");
            });
            throw new ApiException(422, e.code(), "Refund failed: " + e.getMessage());
        }
        return tx.execute(s -> {
            rows.lock(TABLE, id);
            ObjectNode v = rows.object();
            v.put("status", "COMPLETED");
            v.put("approved_by", TenantContext.principal());
            v.put("wallet_transaction_id", txnId);
            rows.update(TABLE, id, v);
            rows.execute("update merchant_refund set approved_at = now(), completed_at = now() where id = ?", id);
            ObjectNode after = rows.get(TABLE, id);
            events.publish("wallets.merchant_refund.completed", TABLE, after);
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
            return after;
        });
    }

    private BigDecimal refundFee(ObjectNode m) {
        if (!m.hasNonNull("merchant_fee_plan_id")) {
            return BigDecimal.ZERO;
        }
        BigDecimal f = Rows.dec(rows.get("merchant_fee_plan", Rows.uuid(m, "merchant_fee_plan_id")), "refund_fee");
        return f == null ? BigDecimal.ZERO : f;
    }

    public ObjectNode reject(UUID id, String reason) {
        return tx.execute(s -> {
            ObjectNode r = rows.lock(TABLE, id);
            String status = Rows.str(r, "status");
            if (!PENDING.contains(status)) {
                throw ApiException.conflict("invalid_transition", "The refund is " + status);
            }
            if ("PENDING_BANK_APPROVAL".equals(status) && merchantUser(TenantContext.principal())) {
                throw ApiException.conflict("awaiting_bank_approval",
                        "The refund was approved by the merchant and waits for the bank's checkers");
            }
            ObjectNode v = rows.object();
            v.put("status", "REJECTED");
            v.put("rejection_reason", reason);
            v.put("approved_by", TenantContext.principal());
            ObjectNode after = rows.update(TABLE, id, v);
            events.publish("wallets.merchant_refund.rejected", TABLE, after);
            approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
            return after;
        });
    }
}
