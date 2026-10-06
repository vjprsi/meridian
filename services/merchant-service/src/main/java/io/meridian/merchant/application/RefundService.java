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
                    where original_transaction_id = ? and status in ('REQUESTED', 'APPROVED', 'COMPLETED')""",
                    BigDecimal.class, original).orElse(BigDecimal.ZERO);
            if (already.add(amount).compareTo(paid) > 0) {
                throw ApiException.unprocessable("refund_exceeds_amount", "Refunds would exceed the payment of "
                        + paid.toPlainString() + " (" + already.toPlainString() + " already refunded or pending)");
            }
            v.put("status", "REQUESTED");
            v.put("idempotency_key", key);
            ObjectNode row = rows.insert(TABLE, v);
            // Central maker-checker: the checker decides in the workflow inbox; RefundApprovalHandler applies it.
            UUID id = Rows.uuid(row, "id");
            BigDecimal amt = amount.setScale(2, RoundingMode.UNNECESSARY);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("merchant_id", merchantId.toString());
            payload.put("merchant_code", Rows.str(m, "merchant_code"));
            payload.put("trading_name", Rows.str(m, "trading_name"));
            payload.put("original_transaction_id", original.toString());
            payload.put("original_reference", o.path("reference").asString(null));
            payload.put("original_amount", paid.toPlainString());
            payload.put("already_refunded_or_pending", already.toPlainString());
            payload.put("amount", amt.toPlainString());
            payload.put("currency", Rows.str(row, "currency"));
            payload.put("reason", Rows.str(row, "reason"));
            approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                            "Refund " + amt.toPlainString() + " " + Rows.str(row, "currency") + " by merchant "
                                    + Rows.str(m, "trading_name"), payload)
                    .withAmount(amt, Rows.str(row, "currency")), "approve:" + id);
            return row;
        });
    }

    /** True while the refund is REQUESTED for the submitted amount and payment (the submission's fingerprint). */
    public boolean awaitsApproval(UUID id, String amount, String originalTransactionId) {
        return rows.find(TABLE, id)
                .filter(r -> "REQUESTED".equals(Rows.str(r, "status")))
                .filter(r -> amount == null || new BigDecimal(amount).compareTo(Rows.dec(r, "amount")) == 0)
                .filter(r -> originalTransactionId == null
                        || originalTransactionId.equals(Rows.str(r, "original_transaction_id")))
                .isPresent();
    }

    public ObjectNode approve(UUID id, String comment) {
        ObjectNode r = rows.get(TABLE, id);
        if (!"REQUESTED".equals(Rows.str(r, "status"))) {
            throw ApiException.conflict("invalid_transition", "The refund is " + Rows.str(r, "status"));
        }
        if (TenantContext.principal().equals(Rows.str(r, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "A refund must be approved by a different user than its requester");
        }
        ObjectNode m = rows.get("merchant", Rows.uuid(r, "merchant_id"));
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
            if (!"REQUESTED".equals(Rows.str(r, "status"))) {
                throw ApiException.conflict("invalid_transition", "The refund is " + Rows.str(r, "status"));
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
