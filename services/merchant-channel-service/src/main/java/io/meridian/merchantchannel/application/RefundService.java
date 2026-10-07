package io.meridian.merchantchannel.application;

import static io.meridian.merchantchannel.application.CurrentUser.merchant;
import static io.meridian.merchantchannel.application.CurrentUser.userId;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.FourEyes;
import io.meridian.common.time.Clocks;
import io.meridian.events.OutboxWriter;
import io.meridian.merchantchannel.application.Downstream.Query;
import io.meridian.merchantchannel.domain.RefundCap;
import io.meridian.web.security.MerchantContext.MerchantPrincipal;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Refunds with four-eyes approval in the app.
 *
 * <ul>
 *   <li>Request (OWNER, MANAGER or CASHIER of the payment's store): a completed MERCHANT_PAYMENT of the merchant; the
 *       amount may not exceed what remains of the payment after completed refunds, refunds pending in merchant-service
 *       and requests pending here ({@link RefundCap}), checked under an advisory lock per payment.</li>
 *   <li>Approve (OWNER or MANAGER of the store, never the requester, recent authentication): the cap is checked again,
 *       then the refund is created in merchant-service <em>as the requester</em> ({@code mrq-create:<id>}) and approved
 *       there <em>as the approver</em> ({@code mrq-approve:<id>:<approver>}), so merchant-service's own four-eyes rule
 *       compares the same two people and a retry never refunds twice. A refusal downstream marks the request FAILED; an
 *       outage leaves it pending for a retry. When merchant-service's bank-review policy applies (amount threshold,
 *       merchant risk) the refund waits as PENDING_BANK_APPROVAL in the bank's central approval inbox: the request
 *       follows it (COMPLETED / REJECTED / FAILED, synced on read); the channel never decides the bank's part.</li>
 *   <li>Reject (OWNER or MANAGER of the store, never the requester) and cancel (requester, OWNER or MANAGER).</li>
 * </ul>
 */
@Service
public class RefundService {
    static final String TABLE = "merchant_refund_request";
    static final String MERCHANTS = Access.MERCHANTS;
    static final Set<String> REQUESTERS = Set.of("OWNER", "MANAGER", "CASHIER");
    static final Set<String> APPROVERS = Set.of("OWNER", "MANAGER");

    private final Access access;
    private final Rows rows;
    private final Downstream down;
    private final StepUp stepUp;
    private final ActionAudit audit;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;

    public RefundService(Access access, Rows rows, Downstream down, StepUp stepUp, ActionAudit audit, OutboxWriter outbox,
                         TransactionTemplate tx) {
        this.access = access;
        this.rows = rows;
        this.down = down;
        this.stepUp = stepUp;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ reads

    public JsonNode list(String status, UUID storeId, Integer limit, String cursor) {
        syncBankDecisions();
        int n = Rows.limit(limit);
        UUID after = Rows.cursor(cursor);
        if (storeId != null) {
            access.store(storeId, "merchantAppRefundsList");
        }
        java.util.Set<UUID> scope = access.scopeStoreIds();
        String stores = scope == null ? null : Rows.uuidArray(scope);
        return rows.page(rows.list("select row_to_json(t)::text from merchant_refund_request t where t.merchant_id = ? "
                        + "and (?::uuid is null or t.merchant_store_id = ?::uuid) "
                        + "and (?::uuid[] is null or t.merchant_store_id = any(?::uuid[])) "
                        + "and (?::text is null or t.status = ?::text) "
                        + "and (?::uuid is null or t.id < ?::uuid) order by t.id desc limit ?",
                merchant(), storeId, storeId, stores, stores, status, status, after, after, n + 1), n);
    }

    public JsonNode get(UUID id) {
        ObjectNode r = request(id, "merchantAppRefundsGet");
        return "PENDING_BANK_APPROVAL".equals(r.path("status").asString()) ? syncBankDecision(r) : r;
    }

    /** Brings the merchant's requests waiting for the bank up to date (oldest first, bounded per call). */
    void syncBankDecisions() {
        for (ObjectNode r : rows.list("select row_to_json(t)::text from merchant_refund_request t where t.merchant_id = ? "
                + "and t.status = 'PENDING_BANK_APPROVAL' order by t.id limit 20", merchant())) {
            syncBankDecision(r);
        }
    }

    /**
     * A request waiting for the bank's checkers follows its refund in merchant-service: COMPLETED (refund executed),
     * REJECTED by the bank, or FAILED. The bank's decision is never taken here.
     */
    ObjectNode syncBankDecision(ObjectNode r) {
        String refundId = r.path("merchant_refund_id").asString(null);
        if (refundId == null) {
            return r;
        }
        JsonNode refund;
        try {
            refund = down.get(MERCHANTS, "/v1/merchant-refunds/" + refundId);
        } catch (ApiException e) {
            return r; // stays as it is; the next read tries again
        }
        String status = refund.path("status").asString("");
        if (!java.util.Set.of("COMPLETED", "REJECTED", "FAILED").contains(status)) {
            return r;
        }
        UUID id = Req.rowUuid(r, "id");
        return tx.execute(s -> {
            ObjectNode locked = rows.lock(TABLE, merchant(), id).orElseThrow(() -> Access.notFound("REFUND", id));
            if (!"PENDING_BANK_APPROVAL".equals(locked.path("status").asString())) {
                return locked;
            }
            ObjectNode v = rows.object().put("status", status)
                    .put("bank_decided_by", refund.path("approved_by").asString(null));
            switch (status) {
                case "COMPLETED" -> v.put("refund_transaction_id", refund.path("wallet_transaction_id").asString(null));
                case "REJECTED" -> v.put("failure_reason", truncate("Rejected by the bank: "
                        + refund.path("rejection_reason").asString(""), 255));
                default -> v.put("failure_reason", truncate(refund.path("failure_reason").asString("failed"), 255));
            }
            ObjectNode a = rows.update(TABLE, id, v);
            if (!"FAILED".equals(status)) {
                outbox.publish("channels.merchant_refund_request." + status.toLowerCase(java.util.Locale.ROOT), TABLE, id, a);
            }
            return a;
        });
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * What remains refundable on a payment: refunds of merchant-service (COMPLETED = refunded; REQUESTED / APPROVED =
     * pending) and the app's pending requests not yet created there. {@code except} (a request being approved) and its
     * merchant-service refund are left out.
     */
    public RefundCap capacity(JsonNode txn, ObjectNode except) {
        BigDecimal amount = Req.rowDecimal(txn, "amount");
        String txnId = txn.path("id").asString();
        String exceptRefund = except == null ? null : except.path("merchant_refund_id").asString(null);
        BigDecimal refunded = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        for (JsonNode r : down.all(MERCHANTS, "/v1/merchant-refunds", Query.of("merchant_id", merchant())
                .with("original_transaction_id", txnId), 1000)) {
            if (!Access.ofMerchant(r) || !txnId.equals(r.path("original_transaction_id").asString(null))
                    || r.path("id").asString("").equals(exceptRefund)) {
                continue;
            }
            switch (r.path("status").asString("")) {
                case "COMPLETED" -> refunded = refunded.add(Req.rowDecimal(r, "amount"));
                case "REQUESTED", "PENDING_BANK_APPROVAL", "APPROVED" -> pending = pending.add(Req.rowDecimal(r, "amount"));
                default -> { }
            }
        }
        BigDecimal local = rows.scalar("select coalesce(sum(amount), 0) from merchant_refund_request where merchant_id = ? "
                        + "and original_transaction_id = ? and status = 'PENDING_APPROVAL' and merchant_refund_id is null "
                        + "and (?::uuid is null or id <> ?::uuid)", BigDecimal.class, merchant(), UUID.fromString(txnId),
                except == null ? null : UUID.fromString(except.path("id").asString()),
                except == null ? null : UUID.fromString(except.path("id").asString())).orElse(BigDecimal.ZERO);
        return new RefundCap(amount, refunded, pending.add(local));
    }

    // ------------------------------------------------------------------ request

    public JsonNode create(JsonNode req) {
        String action = "merchantAppRefundsCreate";
        UUID original = Req.uuid(req, "original_transaction_id", true);
        JsonNode txn = access.transaction(original, action);
        access.requireRole(action, "REFUND", null, REQUESTERS.toArray(String[]::new));
        if (!"MERCHANT_PAYMENT".equals(txn.path("txn_type").asString(""))
                || !"COMPLETED".equals(txn.path("status").asString(""))) {
            throw ApiException.unprocessable("not_refundable", "Only a completed payment to the merchant can be refunded");
        }
        BigDecimal amount = Req.amount(req, "amount");
        String reason = Req.required(req, "reason");
        UUID store = access.storeOf(txn, new HashMap<>());
        UUID sale = rows.one("select row_to_json(t)::text from merchant_sale t where t.merchant_id = ? "
                        + "and t.wallet_transaction_id = ?", merchant(), original)
                .map(s -> UUID.fromString(s.path("id").asString())).orElse(null);
        ObjectNode saved = tx.execute(s -> {
            rows.lockKey("merchant-refund:" + original);
            RefundCap cap = capacity(txn, null);
            if (!cap.allows(amount)) {
                throw exceeds(cap);
            }
            ObjectNode v = rows.object()
                    .put("merchant_id", merchant().toString())
                    .put("merchant_store_id", store == null ? null : store.toString())
                    .put("original_transaction_id", original.toString())
                    .put("merchant_sale_id", sale == null ? null : sale.toString())
                    .put("amount", amount.toPlainString())
                    .put("currency", txn.path("currency").asString())
                    .put("reason", reason.length() > 255 ? reason.substring(0, 255) : reason)
                    .put("status", "PENDING_APPROVAL")
                    .put("requested_by", userId().toString());
            ObjectNode r = rows.insert(TABLE, v);
            outbox.publish("channels.merchant_refund_request.requested", TABLE, UUID.fromString(r.path("id").asString()), r);
            return r;
        });
        audit.succeeded(action, "REFUND", original, saved.path("id").asString());
        return saved;
    }

    // ------------------------------------------------------------------ decisions

    public JsonNode approve(UUID id, JsonNode req) {
        String action = "merchantAppRefundsApprove";
        ObjectNode r = request(id, action);
        access.requireRole(action, "REFUND", id, APPROVERS.toArray(String[]::new));
        pending(r);
        notRequester(r, action, id);
        stepUp.require(action, "REFUND", id);
        String comment = Req.text(req, "comment");
        UUID original = Req.rowUuid(r, "original_transaction_id");
        JsonNode txn = access.transaction(original, action);
        BigDecimal amount = Req.rowDecimal(r, "amount").setScale(2, java.math.RoundingMode.HALF_EVEN);
        ObjectNode checked = tx.execute(s -> {
            rows.lockKey("merchant-refund:" + original);
            ObjectNode locked = rows.lock(TABLE, merchant(), id).orElseThrow(() -> Access.notFound("REFUND", id));
            pending(locked);
            RefundCap cap = capacity(txn, locked);
            if (!cap.allows(amount)) {
                throw exceeds(cap);
            }
            return locked;
        });
        UUID requester = Req.rowUuid(checked, "requested_by");
        UUID approver = userId();
        Integer authAge = stepUp.authAgeSeconds();
        // 1. the refund in merchant-service, as the requester (deterministic key: a retry never creates a second one)
        String refundId = checked.path("merchant_refund_id").asString(null);
        if (refundId == null) {
            ObjectNode body = rows.object()
                    .put("merchant_id", merchant().toString())
                    .put("original_transaction_id", original.toString())
                    .put("amount", amount.toPlainString())
                    .put("currency", checked.path("currency").asString())
                    .put("reason", checked.path("reason").asString());
            JsonNode created;
            try {
                created = down.postAs(requester, MERCHANTS, "/v1/merchant-refunds", body, "mrq-create:" + id);
            } catch (ApiException e) {
                if (Downstream.rejected(e)) {
                    return failed(id, approver, comment, e, action);
                }
                throw e;
            }
            refundId = created.path("id").asString();
            String rid = refundId;
            tx.executeWithoutResult(s -> rows.update(TABLE, id, rows.object().put("merchant_refund_id", rid)));
        }
        // 2. its approval in merchant-service, as the approver (four-eyes there compares the same two people)
        JsonNode done;
        try {
            done = down.postAs(approver, MERCHANTS, "/v1/merchant-refunds/" + refundId + "/approve",
                    rows.object().put("comment", comment), "mrq-approve:" + id + ":" + approver);
        } catch (ApiException e) {
            if (e.status() == 409) {
                done = down.get(MERCHANTS, "/v1/merchant-refunds/" + refundId);   // decided meanwhile (a retry)
            } else if (Downstream.rejected(e)) {
                return failed(id, approver, comment, e, action);
            } else {
                throw e;
            }
        }
        if ("PENDING_BANK_APPROVAL".equals(done.path("status").asString(""))) {
            // the bank reviews it (amount threshold / merchant risk): its checkers decide in the central inbox; the
            // channel never approves the bank-side request
            ObjectNode waiting = tx.execute(s -> {
                rows.lock(TABLE, merchant(), id);
                ObjectNode v = rows.object().put("status", "PENDING_BANK_APPROVAL")
                        .put("decided_by", approver.toString())
                        .put("decided_at", Clocks.now().toString())
                        .put("decision_comment", comment);
                if (authAge != null) {
                    v.put("approver_auth_age_seconds", authAge);
                }
                ObjectNode a = rows.update(TABLE, id, v);
                outbox.publish("channels.merchant_refund_request.pending_bank_approval", TABLE, id, a);
                return a;
            });
            audit.succeeded(action, "REFUND", id, Req.rowUuid(waiting, "merchant_refund_id"));
            return waiting;
        }
        if (!"COMPLETED".equals(done.path("status").asString(""))) {
            ApiException e = new ApiException(422, "refund_not_completed", "merchant-service reports the refund "
                    + done.path("status").asString("") + ": " + done.path("failure_reason").asString(""));
            return failed(id, approver, comment, e, action);
        }
        JsonNode result = done;
        ObjectNode after = tx.execute(s -> {
            rows.lock(TABLE, merchant(), id);
            ObjectNode v = rows.object().put("status", "COMPLETED")
                    .put("decided_by", approver.toString())
                    .put("decided_at", Clocks.now().toString())
                    .put("decision_comment", comment)
                    .put("refund_transaction_id", result.path("wallet_transaction_id").asString(null));
            if (authAge != null) {
                v.put("approver_auth_age_seconds", authAge);
            }
            ObjectNode a = rows.update(TABLE, id, v);
            outbox.publish("channels.merchant_refund_request.completed", TABLE, id, a);
            return a;
        });
        audit.succeeded(action, "REFUND", id, Req.rowUuid(after, "merchant_refund_id"));
        return after;
    }

    public JsonNode reject(UUID id, JsonNode req) {
        String action = "merchantAppRefundsReject";
        ObjectNode r = request(id, action);
        access.requireRole(action, "REFUND", id, APPROVERS.toArray(String[]::new));
        pending(r);
        notRequester(r, action, id);
        String reason = Req.required(req, "reason");
        ObjectNode after = tx.execute(s -> {
            ObjectNode locked = rows.lock(TABLE, merchant(), id).orElseThrow(() -> Access.notFound("REFUND", id));
            pending(locked);
            if (locked.hasNonNull("merchant_refund_id")) {
                throw ApiException.conflict("refund_in_progress", "The refund is being executed; retry the approval");
            }
            ObjectNode a = rows.update(TABLE, id, rows.object().put("status", "REJECTED")
                    .put("decided_by", userId().toString()).put("decided_at", Clocks.now().toString())
                    .put("decision_comment", reason.length() > 500 ? reason.substring(0, 500) : reason));
            outbox.publish("channels.merchant_refund_request.rejected", TABLE, id, a);
            return a;
        });
        audit.succeeded(action, "REFUND", id, null);
        return after;
    }

    public JsonNode cancel(UUID id) {
        String action = "merchantAppRefundsCancel";
        ObjectNode r = request(id, action);
        boolean requester = userId().toString().equals(r.path("requested_by").asString(null));
        if (!requester) {
            access.requireRole(action, "REFUND", id, APPROVERS.toArray(String[]::new));
        }
        pending(r);
        ObjectNode after = tx.execute(s -> {
            ObjectNode locked = rows.lock(TABLE, merchant(), id).orElseThrow(() -> Access.notFound("REFUND", id));
            pending(locked);
            if (locked.hasNonNull("merchant_refund_id")) {
                throw ApiException.conflict("refund_in_progress", "The refund is being executed; it cannot be withdrawn");
            }
            return rows.update(TABLE, id, rows.object().put("status", "CANCELLED")
                    .put("decided_by", userId().toString()).put("decided_at", Clocks.now().toString()));
        });
        audit.succeeded(action, "REFUND", id, null);
        return after;
    }

    // ------------------------------------------------------------------ helpers

    /** A refund request of the merchant in a store of the user's scope; else 404 (audited). */
    ObjectNode request(UUID id, String action) {
        ObjectNode r = rows.find(TABLE, merchant(), id).orElseThrow(() -> access.deny(action, "REFUND", id));
        access.requireScope(Req.rowUuid(r, "merchant_store_id"), action, "REFUND", id);
        return r;
    }

    private static void pending(ObjectNode r) {
        if (!"PENDING_APPROVAL".equals(r.path("status").asString())) {
            throw ApiException.conflict("invalid_transition", "The refund request is " + r.path("status").asString());
        }
    }

    private void notRequester(ObjectNode r, String action, UUID id) {
        String requester = r.path("requested_by").asString(null);
        if (userId().toString().equals(requester)) {
            audit.denied(action, "REFUND", id, "four_eyes_violation");
        }
        FourEyes.require(requester == null ? null : MerchantPrincipal.principalName(UUID.fromString(requester)),
                MerchantPrincipal.principalName(userId()), "A refund must be decided by another user than its requester");
    }

    private static ApiException exceeds(RefundCap cap) {
        return ApiException.unprocessable("refund_exceeds_remaining", "At most " + cap.refundable().toPlainString()
                + " can still be refunded on this payment (" + cap.refunded().toPlainString() + " refunded, "
                + cap.pending().toPlainString() + " pending)");
    }

    private ObjectNode failed(UUID id, UUID approver, String comment, ApiException e, String action) {
        String msg = e.code() + ": " + e.getMessage();
        ObjectNode after = tx.execute(s -> {
            rows.lock(TABLE, merchant(), id);
            return rows.update(TABLE, id, rows.object().put("status", "FAILED")
                    .put("decided_by", approver.toString()).put("decided_at", Clocks.now().toString())
                    .put("decision_comment", comment)
                    .put("failure_reason", msg.length() > 255 ? msg.substring(0, 255) : msg));
        });
        audit.failed(action, "REFUND", id, e.code());
        return after;
    }
}
