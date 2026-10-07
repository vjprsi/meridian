package io.meridian.businesschannel.application;

import static io.meridian.businesschannel.application.CurrentUser.business;
import static io.meridian.businesschannel.application.CurrentUser.userId;

import io.meridian.businesschannel.domain.ApprovalPolicy;
import io.meridian.businesschannel.domain.ApprovalPolicy.Plan;
import io.meridian.common.error.ApiException;
import io.meridian.common.time.Clocks;
import io.meridian.events.OutboxWriter;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The approval matrix for non-payment operations: loan and credit-line repayments, credit-line draws, mandates,
 * collections, virtual accounts and payment links.
 *
 * <p>When an active rule of the operation's type covers its currency and amount, the operation is not executed: the
 * downstream request (service, path and body, rebuilt server-side by the caller after every ownership, role,
 * entitlement and step-up check) is stored on a {@code business_payment} of that kind, PENDING_APPROVAL with the rule's
 * plan snapshot and a checksum over the request. It is then approved like a payment (entitled approvers on the account
 * involved, never the initiator, designated / sequential approvers) and released by {@link ReleaseService} exactly
 * once (row lock, Idempotency-Key {@code bzp-release:<id>}, as the initiator). Without a covering rule the operation
 * runs immediately, as before.
 */
@Service
public class OperationApprovals {
    /** The result of an operation: the downstream object (executed now) or the business payment holding it (pending). */
    public record Outcome(JsonNode body, boolean pending) {}

    private final Rows rows;
    private final Downstream down;
    private final ApprovalRuleService rules;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final BusinessDay days;

    public OperationApprovals(Rows rows, Downstream down, ApprovalRuleService rules, OutboxWriter outbox,
                              TransactionTemplate tx, BusinessDay days) {
        this.rows = rows;
        this.down = down;
        this.rules = rules;
        this.outbox = outbox;
        this.tx = tx;
        this.days = days;
    }

    /**
     * Runs the operation now, or holds it for approval when a rule of type {@code kind} covers it.
     *
     * @param kind      operation type (BusinessPaymentKind / BusinessPaymentScope value)
     * @param account   the business account involved (debited, credited or master), already checked by the caller
     * @param amount    the amount the matrix bands are compared with (zero when the operation has none)
     * @param label     short description stored as the business payment's name
     * @param clientKey the client's Idempotency-Key (immediate execution derives the downstream key from it)
     */
    public Outcome run(String kind, JsonNode account, BigDecimal amount, String label, String service, String path,
                       ObjectNode body, String clientKey, String step) {
        if (!ApprovalPolicy.OPERATION_TYPES.contains(kind)) {
            throw new IllegalArgumentException("not an operation type: " + kind);
        }
        String currency = account.path("currency").asString();
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        Optional<Plan> plan = ApprovalPolicy.covering(rules.activeRules(), kind, currency, value);
        if (plan.isEmpty()) {
            return new Outcome(down.post(service, path, body, clientKey, step), false);
        }
        ApprovalPolicy.unsatisfiable(plan.get(), userId()).ifPresent(msg -> {
            throw ApiException.unprocessable("approval_matrix_unsatisfiable", msg);
        });
        ObjectNode op = rows.object().put("service", service).put("path", path);
        op.set("body", body);
        ObjectNode p = rows.object()
                .put("business_customer_id", business().toString())
                .put("kind", kind)
                .put("status", "PENDING_APPROVAL")
                .put("debtor_account_id", account.path("id").asString())
                .put("amount", value.toPlainString())
                .put("currency", currency)
                .put("name", label == null ? null : label.length() > 140 ? label.substring(0, 140) : label)
                .put("initiated_by", userId().toString())
                .put("submitted_at", Clocks.now().toString())
                .put("business_date", days.today(account).toString())
                .put("approval_rule_id", plan.get().ruleId() == null ? null : plan.get().ruleId().toString())
                .put("required_approvals", plan.get().required())
                .put("approval_order", plan.get().order())
                .put("approvals_received", 0);
        ArrayNode ids = p.putArray("approver_ids");
        plan.get().approvers().forEach(a -> ids.add(a.toString()));
        p.set("operation", op);
        ObjectNode saved = tx.execute(s -> {
            ObjectNode r = rows.insert(PaymentService.TABLE, p);
            UUID id = UUID.fromString(r.path("id").asString());
            r = rows.update(PaymentService.TABLE, id, rows.object().put("checksum", PaymentService.checksum(r)));
            outbox.publish("channels.business_payment.submitted", PaymentService.TABLE, id, r);
            return r;
        });
        return new Outcome(saved, true);
    }
}
