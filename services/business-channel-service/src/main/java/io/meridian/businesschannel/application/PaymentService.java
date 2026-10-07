package io.meridian.businesschannel.application;

import static io.meridian.businesschannel.application.CurrentUser.business;
import static io.meridian.businesschannel.application.CurrentUser.userId;

import io.meridian.businesschannel.application.Access.Need;
import io.meridian.businesschannel.config.BusinessChannelProperties;
import io.meridian.businesschannel.domain.ApprovalPolicy;
import io.meridian.businesschannel.domain.ApprovalPolicy.Plan;
import io.meridian.businesschannel.domain.BatchCsv;
import io.meridian.businesschannel.domain.PaymentContent;
import io.meridian.common.error.ApiException;
import io.meridian.common.time.Clocks;
import io.meridian.events.OutboxWriter;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Business payments, bulk payments and payroll with multi-level approvals.
 *
 * <p>Lifecycle: a MAKER (or ADMIN) entitled to initiate on the debit account drafts a payment (DRAFT; body rebuilt
 * server-side, content checksum fixed), then submits it (PENDING_APPROVAL): entitlement, per-payment cap and the
 * initiator's daily limit are checked under a lock on the user, and the approval matrix rule is snapshot. Entitled
 * APPROVER / ADMIN users approve with a recent authentication; the initiator never, nobody twice, only designated
 * approvers when the rule names them and in their order when it is SEQUENTIAL. The final approval (APPROVED) releases
 * the payment to payment-service ({@link ReleaseService}); any approver may reject (REJECTED); the initiator or an
 * ADMIN may cancel a draft or pending payment (CANCELLED).
 */
@Service
public class PaymentService {
    static final String TABLE = "business_payment";
    static final String APPROVALS = "business_payment_approval";
    static final Set<String> SINGLE_KINDS = Set.of("OWN_TRANSFER", "DOMESTIC", "INTERNATIONAL");
    static final Set<String> BATCH_KINDS = Set.of("BULK", "PAYROLL");
    static final Set<String> COUNTED_FOR_LIMIT = Set.of("PENDING_APPROVAL", "APPROVED", "RELEASED", "RELEASE_FAILED");
    /** Kinds the daily limit counts (payments; not the non-payment operations held by the matrix). */
    static final Set<String> PAYMENT_KINDS = Set.of("OWN_TRANSFER", "DOMESTIC", "INTERNATIONAL", "BULK", "PAYROLL");

    private final Rows rows;
    private final Access access;
    private final StepUp stepUp;
    private final ActionAudit audit;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final ApprovalRuleService rules;
    private final ReleaseService release;
    private final Downstream down;
    private final BusinessChannelProperties props;
    private final BusinessDay days;

    public PaymentService(Rows rows, Access access, StepUp stepUp, ActionAudit audit, OutboxWriter outbox,
                          TransactionTemplate tx, ApprovalRuleService rules, ReleaseService release, Downstream down,
                          BusinessChannelProperties props, BusinessDay days) {
        this.days = days;
        this.rows = rows;
        this.access = access;
        this.stepUp = stepUp;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
        this.rules = rules;
        this.release = release;
        this.down = down;
        this.props = props;
    }

    // ------------------------------------------------------------------ reading

    public JsonNode list(String status, String kind, UUID accountId, Integer limit, String cursor) {
        List<UUID> accounts = new ArrayList<>();
        if (accountId != null) {
            access.account(accountId, Need.VIEW, "businessPaymentsList");
            accounts.add(accountId);
        } else {
            accounts.addAll(access.viewableAccountIds());
        }
        int n = Rows.limit(limit);
        if (accounts.isEmpty()) {
            return rows.page(List.of());
        }
        UUID after = Rows.cursor(cursor);
        return rows.page(rows.list("select row_to_json(t)::text from business_payment t where t.business_customer_id = ? "
                        + "and t.debtor_account_id = any(?::uuid[]) and (?::text is null or t.status = ?::text) "
                        + "and (?::text is null or t.kind = ?::text) and (?::uuid is null or t.id < ?::uuid) "
                        + "order by t.id desc limit ?", business(), Rows.uuidArray(accounts), status, status, kind, kind,
                after, after, n + 1), n);
    }

    public JsonNode get(UUID id) {
        return payment(id, "businessPaymentsGet");
    }

    public JsonNode approvals(UUID id) {
        payment(id, "businessPaymentsApprovals");
        return rows.page(decisions(id));
    }

    /** Payments the user may approve now. */
    public JsonNode queue(Integer limit) {
        int n = Rows.limit(limit);
        if (!Set.of("ADMIN", "APPROVER").contains(CurrentUser.role())) {
            return rows.page(List.of());
        }
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode p : rows.list("select row_to_json(t)::text from business_payment t where t.business_customer_id = ? "
                + "and t.status = 'PENDING_APPROVAL' and t.initiated_by <> ? order by t.submitted_at limit 1000",
                business(), userId())) {
            UUID debtor = UUID.fromString(p.path("debtor_account_id").asString());
            if (access.entitled(debtor, Need.APPROVE) && ApprovalPolicy.refusal(plan(p), initiator(p),
                    approvedBy(UUID.fromString(p.path("id").asString())), userId()).isEmpty()) {
                out.add(p);
                if (out.size() >= n) {
                    break;
                }
            }
        }
        return rows.page(out);
    }

    /** The released payment (or bulk payment) as payment-service has it. */
    public JsonNode downstream(UUID id) {
        ObjectNode p = payment(id, "businessPaymentsDownstream");
        ObjectNode out = rows.object();
        if (p.hasNonNull("payment_id")) {
            String pid = p.path("payment_id").asString();
            out.put("type", "PAYMENT");
            out.set("payment", down.get("payment-service", "/v1/payments/" + pid));
        } else if (p.hasNonNull("bulk_payment_id")) {
            out.put("type", "BULK_PAYMENT");
            out.set("bulk_payment", down.get("payment-service", "/v1/bulk-payments/" + p.path("bulk_payment_id").asString()));
        } else if (p.hasNonNull("operation") && "RELEASED".equals(p.path("status").asString())) {
            out.put("type", "OPERATION");
            out.put("kind", p.path("kind").asString());
            out.put("result_id", p.path("result_id").asString(null));
            out.put("downstream_status", p.path("downstream_status").asString(null));
        } else {
            throw ApiException.conflict("not_released", "The payment has not been released to the bank yet");
        }
        return out;
    }

    public String bulkReport(UUID id) {
        ObjectNode p = payment(id, "businessBulkPaymentsReport");
        if (!BATCH_KINDS.contains(p.path("kind").asString()) || !p.hasNonNull("bulk_payment_id")) {
            throw ApiException.conflict("not_released", "The batch has not been released to the bank yet");
        }
        ServiceClient.Download d = down.bytes("payment-service", "/v1/bulk-payments/"
                + p.path("bulk_payment_id").asString() + "/report");
        return new String(d.content(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ drafting

    public JsonNode create(JsonNode req) {
        String action = "businessPaymentsCreate";
        access.requireRole(action, "PAYMENT", null, "ADMIN", "MAKER");
        String kind = Req.required(req, "kind");
        if (BATCH_KINDS.contains(kind)) {
            throw ApiException.unprocessable("use_bulk_upload", "Bulk payments and payroll are uploaded as CSV");
        }
        if (!SINGLE_KINDS.contains(kind)) {
            throw ApiException.unprocessable("invalid_kind", "kind must be OWN_TRANSFER, DOMESTIC or INTERNATIONAL");
        }
        UUID debtorId = Req.uuid(req, "debtor_account_id", true);
        JsonNode debtor = access.account(debtorId, Need.INITIATE, action);
        requireUsable(debtor);
        BigDecimal amount = Req.amount(req, "amount");
        requireWithinCap(debtorId, amount, action);
        String currency = debtor.path("currency").asString();
        ObjectNode p = rows.object()
                .put("business_customer_id", business().toString())
                .put("kind", kind)
                .put("status", "DRAFT")
                .put("debtor_account_id", debtorId.toString())
                .put("amount", amount.toPlainString())
                .put("currency", currency)
                .put("reference", text(req, "reference", 35))
                .put("remittance", text(req, "remittance", 140))
                .put("purpose_code", purpose(Req.text(req, "purpose_code")))
                .put("initiated_by", userId().toString());
        LocalDate date = Req.date(req, "requested_execution_date");
        if (date != null) {
            if (date.isBefore(days.today(debtor))) {
                throw ApiException.unprocessable("execution_date_in_past", "requested_execution_date is in the past");
            }
            p.put("requested_execution_date", date.toString());
        }
        switch (kind) {
            case "OWN_TRANSFER" -> {
                reject(req, "payee_id", "An own transfer has no payee");
                reject(req, "fx_quote_id", "An own transfer has no FX quote");
                UUID to = Req.uuid(req, "creditor_account_id", true);
                if (to.equals(debtorId)) {
                    throw ApiException.unprocessable("same_account", "Debit and credit account are the same");
                }
                JsonNode credit = access.businessAccount(to, action);
                requireUsable(credit);
                if (!currency.equals(credit.path("currency").asString())) {
                    throw ApiException.unprocessable("currency_mismatch", "Both accounts must be in the same currency");
                }
                p.put("creditor_account_id", to.toString())
                        .put("creditor_name", credit.path("nickname").asString(credit.path("account_number").asString(null)));
            }
            case "DOMESTIC" -> {
                reject(req, "creditor_account_id", "A payee payment has no creditor_account_id");
                reject(req, "fx_quote_id", "A domestic payment has no FX quote; use INTERNATIONAL");
                JsonNode payee = activePayee(Req.uuid(req, "payee_id", true), action);
                String payeeCcy = payee.path("currency").asString(null);
                if (payeeCcy != null && !payeeCcy.equals(currency)) {
                    throw ApiException.unprocessable("currency_mismatch", "The payee is paid in " + payeeCcy
                            + "; use an INTERNATIONAL payment with an FX quote");
                }
                p.put("payee_id", payee.path("id").asString()).put("creditor_name", payee.path("name").asString(null));
            }
            default -> {
                reject(req, "creditor_account_id", "An international payment has no creditor_account_id");
                JsonNode payee = activePayee(Req.uuid(req, "payee_id", true), action);
                UUID quoteId = Req.uuid(req, "fx_quote_id", true);
                JsonNode quote = access.fxQuote(quoteId, action);
                requireQuoteFits(quote, currency, amount, payee.path("currency").asString(null));
                Optional<ObjectNode> bound = rows.one("select row_to_json(t)::text from business_payment t where "
                        + "t.business_customer_id = ? and t.fx_quote_id = ? and t.status not in ('REJECTED','CANCELLED')",
                        business(), quoteId);
                if (bound.isPresent()) {
                    throw ApiException.conflict("fx_quote_in_use", "The FX quote is already bound to payment "
                            + bound.get().path("id").asString());
                }
                p.put("payee_id", payee.path("id").asString()).put("creditor_name", payee.path("name").asString(null))
                        .put("fx_quote_id", quoteId.toString())
                        .put("instructed_amount", quote.path("buy_amount").asString())
                        .put("instructed_currency", quote.path("buy_currency").asString())
                        .put("fx_rate", quote.path("customer_rate").asString(null));
            }
        }
        ObjectNode saved = insertDraft(p);
        audit.succeeded(action, "ACCOUNT", debtorId, saved.path("id").asString());
        return saved;
    }

    /** Uploads a CSV batch as a BULK or PAYROLL draft; line errors are kept on the draft (it cannot be submitted). */
    public JsonNode upload(JsonNode req) {
        String action = "businessBulkPaymentsCreate";
        access.requireRole(action, "PAYMENT", null, "ADMIN", "MAKER");
        String kind = Req.required(req, "kind");
        if (!BATCH_KINDS.contains(kind)) {
            throw ApiException.unprocessable("invalid_kind", "kind must be BULK or PAYROLL");
        }
        UUID debtorId = Req.uuid(req, "debtor_account_id", true);
        JsonNode debtor = access.account(debtorId, Need.INITIATE, action);
        requireUsable(debtor);
        String content = Req.required(req, "content");
        if (content.length() > props.maxBatchBytes()) {
            throw ApiException.unprocessable("file_too_large", "A batch file has at most " + props.maxBatchBytes()
                    + " characters");
        }
        String currency = debtor.path("currency").asString();
        BatchCsv.Batch batch;
        try {
            batch = BatchCsv.parse(content, currency, props.maxBatchLines());
        } catch (BatchCsv.FileProblem e) {
            throw ApiException.unprocessable(e.code(), e.getMessage());
        }
        ObjectNode p = rows.object()
                .put("business_customer_id", business().toString())
                .put("kind", kind)
                .put("status", "DRAFT")
                .put("debtor_account_id", debtorId.toString())
                .put("amount", batch.total().toPlainString())
                .put("currency", currency)
                .put("name", text(req, "name", 140))
                .put("item_count", batch.lines().size())
                .put("invalid_count", batch.invalidLines())
                .put("initiated_by", userId().toString());
        LocalDate date = Req.date(req, "requested_execution_date");
        if (date != null) {
            if (date.isBefore(days.today(debtor))) {
                throw ApiException.unprocessable("execution_date_in_past", "requested_execution_date is in the past");
            }
            p.put("requested_execution_date", date.toString());
        }
        ArrayNode items = p.putArray("items");
        for (Map<String, String> l : batch.lines()) {
            ObjectNode i = items.addObject();
            l.forEach(i::put);
        }
        ArrayNode errors = p.putArray("validation_errors");
        for (BatchCsv.LineError e : batch.errors()) {
            errors.addObject().put("line", e.line()).put("field", e.field()).put("code", e.code()).put("message", e.message());
        }
        ObjectNode saved = insertDraft(p);
        audit.succeeded(action, "ACCOUNT", debtorId, saved.path("id").asString());
        return saved;
    }

    private ObjectNode insertDraft(ObjectNode p) {
        return tx.execute(s -> {
            ObjectNode saved = rows.insert(TABLE, p);
            return rows.update(TABLE, UUID.fromString(saved.path("id").asString()),
                    rows.object().put("checksum", checksum(saved)));
        });
    }

    // ------------------------------------------------------------------ submission

    public JsonNode submit(UUID id) {
        String action = "businessPaymentsSubmit";
        ObjectNode p = payment(id, action);
        if (!userId().equals(initiator(p))) {
            audit.denied(action, "PAYMENT", id, "not_initiator");
            throw new ApiException(403, "not_initiator", "Only the user who drafted the payment can submit it");
        }
        requireStatus(p, Set.of("DRAFT"), "Only a DRAFT can be submitted");
        UUID debtorId = UUID.fromString(p.path("debtor_account_id").asString());
        JsonNode debtor = access.account(debtorId, Need.INITIATE, action);
        LocalDate businessDate = days.today(debtor);
        BigDecimal amount = Req.rowDecimal(p, "amount");
        requireWithinCap(debtorId, amount, action);
        String kind = p.path("kind").asString();
        if (BATCH_KINDS.contains(kind)) {
            if (p.path("invalid_count").asInt() > 0 || p.path("item_count").asInt() == 0 || amount.signum() <= 0) {
                throw ApiException.unprocessable("bulk_has_invalid_items",
                        "The batch has invalid lines (or none); fix the file and upload it again");
            }
        }
        if (p.hasNonNull("fx_quote_id")) {
            JsonNode quote = access.fxQuote(UUID.fromString(p.path("fx_quote_id").asString()), action);
            requireQuoteFits(quote, p.path("currency").asString(), amount, p.path("instructed_currency").asString(null));
        }
        Plan plan = ApprovalPolicy.plan(rules.activeRules(), BATCH_KINDS.contains(kind) ? "BULK" : "SINGLE",
                p.path("currency").asString(), amount, props.defaultRequiredApprovals());
        ApprovalPolicy.unsatisfiable(plan, userId()).ifPresent(msg -> {
            throw ApiException.unprocessable("approval_matrix_unsatisfiable", msg);
        });
        ObjectNode saved = tx.execute(s -> {
            ObjectNode me = rows.lock(CurrentUser.USERS, business(), userId())
                    .orElseThrow(() -> new ApiException(403, "business_user_unknown", "Unknown portal user"));
            ObjectNode locked = rows.lock(TABLE, business(), id).orElseThrow(() -> Access.notFound("PAYMENT", id));
            requireStatus(locked, Set.of("DRAFT"), "Only a DRAFT can be submitted");
            requireDailyLimit(me, locked, businessDate, action);
            ObjectNode c = rows.object()
                    .put("status", "PENDING_APPROVAL")
                    .put("submitted_at", Clocks.now().toString())
                    .put("business_date", businessDate.toString())
                    .put("approval_rule_id", plan.ruleId() == null ? null : plan.ruleId().toString())
                    .put("required_approvals", plan.required())
                    .put("approval_order", plan.order())
                    .put("approvals_received", 0);
            ArrayNode ids = c.putArray("approver_ids");
            plan.approvers().forEach(a -> ids.add(a.toString()));
            ObjectNode r = rows.update(TABLE, id, c);
            outbox.publish("channels.business_payment.submitted", TABLE, id, r);
            return r;
        });
        audit.succeeded(action, "PAYMENT", id, null);
        return saved;
    }

    /**
     * The initiator's submissions on the business date of the debit account's legal entity (not the UTC day), in the
     * limit currency, payments only, plus this one, must stay within the user's daily limit.
     */
    private void requireDailyLimit(ObjectNode me, ObjectNode p, LocalDate businessDate, String action) {
        BigDecimal limit = Req.rowDecimal(me, "daily_limit");
        if (limit == null) {
            return;
        }
        String ccy = me.path("limit_currency").asString(null);
        String currency = p.path("currency").asString();
        if (!currency.equals(ccy)) {
            audit.denied(action, "PAYMENT", p.path("id").asString(), "daily_limit_currency_mismatch");
            throw ApiException.unprocessable("daily_limit_currency_mismatch", "Your daily limit is in " + ccy
                    + "; payments in " + currency + " need an administrator to change it");
        }
        BigDecimal used = rows.scalar("select coalesce(sum(amount), 0) from business_payment where business_customer_id = ? "
                        + "and initiated_by = ? and currency = ? and business_date = ? and status = any(?::text[]) "
                        + "and kind = any(?::text[])",
                BigDecimal.class, business(), userId(), currency, businessDate,
                "{" + String.join(",", COUNTED_FOR_LIMIT) + "}", "{" + String.join(",", PAYMENT_KINDS) + "}")
                .orElse(BigDecimal.ZERO);
        BigDecimal amount = Req.rowDecimal(p, "amount");
        if (used.add(amount).compareTo(limit) > 0) {
            audit.denied(action, "PAYMENT", p.path("id").asString(), "daily_limit_exceeded");
            throw ApiException.unprocessable("daily_limit_exceeded", "This payment would take your submissions today to "
                    + used.add(amount).toPlainString() + " " + currency + ", above your daily limit of "
                    + limit.toPlainString());
        }
    }

    // ------------------------------------------------------------------ decisions

    public JsonNode approve(UUID id, JsonNode req) {
        String action = "businessPaymentsApprove";
        ObjectNode p = decidable(id, action, true);
        stepUp.require(action, "PAYMENT", id);
        String comment = text(req, "comment", 500);
        boolean[] complete = {false};
        ObjectNode saved = tx.execute(s -> {
            ObjectNode locked = rows.lock(TABLE, business(), id).orElseThrow(() -> Access.notFound("PAYMENT", id));
            requireStatus(locked, Set.of("PENDING_APPROVAL"), "The payment is not pending approval");
            List<UUID> decided = approvedBy(id);
            ApprovalPolicy.refusal(plan(locked), initiator(locked), decided, userId()).ifPresent(code -> {
                throw refusal(action, id, code);
            });
            requireChecksum(locked);
            ObjectNode a = rows.object()
                    .put("business_payment_id", id.toString())
                    .put("business_customer_id", business().toString())
                    .put("business_user_id", userId().toString())
                    .put("decision", "APPROVED")
                    .put("sequence", decided.size() + 1)
                    .put("comment", comment)
                    .put("checksum", locked.path("checksum").asString());
            Integer age = stepUp.authAgeSeconds();
            if (age != null) {
                a.put("auth_age_seconds", age);
            }
            rows.insert(APPROVALS, a);
            int received = decided.size() + 1;
            ObjectNode c = rows.object().put("approvals_received", received);
            if (ApprovalPolicy.complete(plan(locked), received)) {
                complete[0] = true;
                c.put("status", "APPROVED").put("released_by", userId().toString());
            }
            ObjectNode r = rows.update(TABLE, id, c);
            if (complete[0]) {
                outbox.publish("channels.business_payment.approved", TABLE, id, r);
            }
            return r;
        });
        audit.succeeded(action, "PAYMENT", id, null);
        return complete[0] ? release.release(id) : saved;
    }

    public JsonNode reject(UUID id, JsonNode req) {
        String action = "businessPaymentsReject";
        decidable(id, action, false);
        String reason = Req.required(req, "reason");
        ObjectNode saved = tx.execute(s -> {
            ObjectNode locked = rows.lock(TABLE, business(), id).orElseThrow(() -> Access.notFound("PAYMENT", id));
            requireStatus(locked, Set.of("PENDING_APPROVAL"), "The payment is not pending approval");
            List<UUID> decided = approvedBy(id);
            ApprovalPolicy.rejectRefusal(plan(locked), initiator(locked), decided, userId()).ifPresent(code -> {
                throw refusal(action, id, code);
            });
            rows.insert(APPROVALS, rows.object()
                    .put("business_payment_id", id.toString())
                    .put("business_customer_id", business().toString())
                    .put("business_user_id", userId().toString())
                    .put("decision", "REJECTED")
                    .put("sequence", decided.size() + 1)
                    .put("comment", reason.length() > 500 ? reason.substring(0, 500) : reason)
                    .put("checksum", locked.path("checksum").asString("")));
            ObjectNode r = rows.update(TABLE, id, rows.object().put("status", "REJECTED")
                    .put("rejected_by", userId().toString())
                    .put("rejection_reason", reason.length() > 255 ? reason.substring(0, 255) : reason));
            outbox.publish("channels.business_payment.rejected", TABLE, id, r);
            return r;
        });
        audit.succeeded(action, "PAYMENT", id, null);
        return saved;
    }

    public JsonNode cancel(UUID id, JsonNode req) {
        String action = "businessPaymentsCancel";
        ObjectNode p = payment(id, action);
        if (!userId().equals(initiator(p)) && !CurrentUser.admin()) {
            audit.denied(action, "PAYMENT", id, "not_initiator");
            throw new ApiException(403, "not_initiator", "Only the initiator or an administrator can cancel the payment");
        }
        ObjectNode saved = tx.execute(s -> {
            ObjectNode locked = rows.lock(TABLE, business(), id).orElseThrow(() -> Access.notFound("PAYMENT", id));
            requireStatus(locked, Set.of("DRAFT", "PENDING_APPROVAL"), "Only a draft or a payment pending approval can be cancelled");
            String reason = Req.text(req, "reason");
            return rows.update(TABLE, id, rows.object().put("status", "CANCELLED").put("cancelled_by", userId().toString())
                    .put("rejection_reason", reason == null ? null : reason.length() > 255 ? reason.substring(0, 255) : reason));
        });
        audit.succeeded(action, "PAYMENT", id, null);
        return saved;
    }

    /** Retries the release of an APPROVED / RELEASE_FAILED payment (never creates a second downstream payment). */
    public JsonNode retryRelease(UUID id) {
        String action = "businessPaymentsRelease";
        ObjectNode p = payment(id, action);
        UUID debtor = UUID.fromString(p.path("debtor_account_id").asString());
        if (!CurrentUser.admin() && !(approvedBy(id).contains(userId()) && access.entitled(debtor, Need.APPROVE))) {
            audit.denied(action, "PAYMENT", id, "not_entitled");
            throw new ApiException(403, "not_entitled", "Only an approver of the payment or an administrator can release it");
        }
        requireStatus(p, Set.of("APPROVED", "RELEASE_FAILED", "RELEASED"), "Only a fully approved payment can be released");
        return release.release(id);
    }

    // ------------------------------------------------------------------ helpers

    /** A payment of the business on an account the user may see; otherwise 404 (audited). */
    ObjectNode payment(UUID id, String action) {
        ObjectNode p = rows.find(TABLE, business(), id).orElseThrow(() -> access.deny(action, "PAYMENT", id));
        if (!access.entitled(UUID.fromString(p.path("debtor_account_id").asString()), Need.VIEW)) {
            audit.denied(action, "PAYMENT", id, "not_entitled");
            throw Access.notFound("PAYMENT", id);
        }
        return p;
    }

    /** Pre-checks of approve / reject before step-up: role, status, entitlement, matrix. */
    private ObjectNode decidable(UUID id, String action, boolean approving) {
        ObjectNode p = payment(id, action);
        access.requireRole(action, "PAYMENT", id, "ADMIN", "APPROVER");
        requireStatus(p, Set.of("PENDING_APPROVAL"), "The payment is not pending approval");
        access.account(UUID.fromString(p.path("debtor_account_id").asString()), Need.APPROVE, action);
        List<UUID> decided = approvedBy(id);
        Optional<String> refusal = approving ? ApprovalPolicy.refusal(plan(p), initiator(p), decided, userId())
                : ApprovalPolicy.rejectRefusal(plan(p), initiator(p), decided, userId());
        refusal.ifPresent(code -> {
            throw refusal(action, id, code);
        });
        return p;
    }

    private ApiException refusal(String action, UUID id, String code) {
        audit.denied(action, "PAYMENT", id, code);
        return switch (code) {
            case "self_approval_forbidden" -> new ApiException(403, code,
                    "You initiated this payment; another approver must decide on it");
            case "already_decided" -> ApiException.conflict(code, "You already decided on this payment");
            case "not_designated_approver" -> new ApiException(403, code,
                    "The approval rule of this payment names other approvers");
            case "out_of_sequence" -> ApiException.conflict(code,
                    "The approval rule is sequential and it is another approver's turn");
            default -> ApiException.unprocessable(code, code);
        };
    }

    static Plan plan(JsonNode p) {
        String rule = p.path("approval_rule_id").asString(null);
        return new Plan(rule == null ? null : UUID.fromString(rule), p.path("required_approvals").asInt(1),
                p.path("approval_order").asString("ANY_ORDER"), UserAdminService.uuids(p.get("approver_ids")));
    }

    static UUID initiator(JsonNode p) {
        return UUID.fromString(p.path("initiated_by").asString());
    }

    /** Users who decided (approved or rejected) on the payment, in order. */
    List<UUID> approvedBy(UUID paymentId) {
        List<UUID> out = new ArrayList<>();
        for (ObjectNode a : decisions(paymentId)) {
            out.add(UUID.fromString(a.path("business_user_id").asString()));
        }
        return out;
    }

    List<ObjectNode> decisions(UUID paymentId) {
        return rows.list("select row_to_json(t)::text from business_payment_approval t where t.business_payment_id = ? "
                + "and t.business_customer_id = ? order by t.sequence", paymentId, business());
    }

    private void requireChecksum(ObjectNode p) {
        if (!checksum(p).equals(p.path("checksum").asString(null))) {
            throw ApiException.conflict("content_changed", "The payment content changed after it was drafted; "
                    + "it cannot be approved");
        }
    }

    static void requireStatus(JsonNode p, Set<String> allowed, String message) {
        if (!allowed.contains(p.path("status").asString(""))) {
            throw ApiException.conflict("invalid_transition", message + " (status " + p.path("status").asString() + ")");
        }
    }

    /** SHA-256 of the payment content as stored. */
    static String checksum(JsonNode p) {
        return PaymentContent.checksum(f -> switch (f) {
            case "amount", "instructed_amount" -> {
                BigDecimal d = Req.rowDecimal(p, f);
                yield d == null ? null : d.toPlainString();
            }
            case "items" -> p.hasNonNull("items") ? PaymentContent.lines(lines(p.get("items"))) : null;
            case "operation" -> p.hasNonNull("operation") ? canonical(p.get("operation")) : null;
            default -> Req.text(p, f);
        });
    }

    /** JSON with object keys sorted at every level (stable whatever order the database returns them in). */
    static String canonical(JsonNode n) {
        if (n == null || n.isNull()) {
            return "null";
        }
        if (n.isObject()) {
            StringBuilder sb = new StringBuilder("{");
            n.propertyNames().stream().sorted().forEach(k -> sb.append(sb.length() > 1 ? "," : "")
                    .append(tools.jackson.databind.node.JsonNodeFactory.instance.textNode(k)).append(':')
                    .append(canonical(n.get(k))));
            return sb.append('}').toString();
        }
        if (n.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < n.size(); i++) {
                sb.append(i > 0 ? "," : "").append(canonical(n.get(i)));
            }
            return sb.append(']').toString();
        }
        return n.toString();
    }

    static List<Map<String, String>> lines(JsonNode items) {
        List<Map<String, String>> out = new ArrayList<>();
        if (items != null && items.isArray()) {
            for (JsonNode i : items) {
                Map<String, String> m = new LinkedHashMap<>();
                for (String k : BatchCsv.COLUMNS) {
                    m.put(k, i.hasNonNull(k) ? i.get(k).asString() : null);
                }
                out.add(m);
            }
        }
        return out;
    }

    private void requireWithinCap(UUID accountId, BigDecimal amount, String action) {
        Optional<ObjectNode> e = access.entitlement(userId(), accountId);
        BigDecimal cap = e.map(x -> Req.rowDecimal(x, "max_amount")).orElse(null);
        if (cap != null && amount.compareTo(cap) > 0) {
            audit.denied(action, "ACCOUNT", accountId, "amount_above_entitlement");
            throw ApiException.unprocessable("amount_above_entitlement", "You may initiate at most "
                    + cap.toPlainString() + " per payment from this account");
        }
    }

    private static void requireUsable(JsonNode account) {
        String status = account.path("status").asString("");
        if (!"ACTIVE".equals(status)) {
            throw ApiException.unprocessable("account_not_active", "The account is " + status);
        }
    }

    private JsonNode activePayee(UUID id, String action) {
        JsonNode payee = access.payee(id, action);
        if (!"ACTIVE".equals(payee.path("status").asString(""))) {
            throw ApiException.unprocessable("payee_not_active", "The payee is not active yet");
        }
        return payee;
    }

    /** SEC-04: the quote is the business's, still valid, sells exactly the payment and buys the payee's currency. */
    static void requireQuoteFits(JsonNode quote, String currency, BigDecimal amount, String payeeCurrency) {
        if (!"ACTIVE".equals(quote.path("status").asString(""))) {
            throw ApiException.unprocessable("fx_quote_not_active", "The FX quote is " + quote.path("status").asString());
        }
        String expires = quote.path("expires_at").asString(null);
        if (expires != null && !OffsetDateTime.parse(expires).isAfter(Clocks.now())) {
            throw ApiException.unprocessable("fx_quote_expired", "The FX quote has expired; request a new quote");
        }
        if (!currency.equals(quote.path("sell_currency").asString(null))) {
            throw ApiException.unprocessable("fx_quote_mismatch", "The FX quote must sell the account currency " + currency);
        }
        BigDecimal sell = Req.rowDecimal(quote, "sell_amount");
        if (sell == null || sell.compareTo(amount) != 0) {
            throw ApiException.unprocessable("fx_quote_amount_mismatch", "The FX quote must sell exactly the payment amount");
        }
        String buy = quote.path("buy_currency").asString(null);
        if (buy == null || buy.equals(currency)) {
            throw ApiException.unprocessable("fx_quote_mismatch", "The FX quote must buy another currency");
        }
        if (payeeCurrency != null && !payeeCurrency.equals(buy)) {
            throw ApiException.unprocessable("fx_quote_mismatch", "The payee is paid in " + payeeCurrency
                    + " but the FX quote buys " + buy);
        }
        if (Req.rowDecimal(quote, "buy_amount") == null) {
            throw ApiException.unprocessable("fx_quote_mismatch", "The FX quote has no buy amount");
        }
    }

    private static void reject(JsonNode req, String field, String message) {
        if (Req.text(req, field) != null) {
            throw ApiException.unprocessable("unexpected_" + field, message);
        }
    }

    private static String text(JsonNode req, String field, int max) {
        String v = Req.text(req, field);
        if (v == null || v.isBlank()) {
            return null;
        }
        v = v.trim();
        if (v.length() > max) {
            throw ApiException.unprocessable("too_long", field + " has at most " + max + " characters");
        }
        return v;
    }

    private static String purpose(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        if (!code.matches("[A-Z]{4}")) {
            throw ApiException.unprocessable("invalid_purpose_code", "purpose_code is a 4-letter ISO 20022 code");
        }
        return code;
    }
}
