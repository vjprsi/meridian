package io.meridian.businesschannel.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The business's approval matrix, without Spring or SQL.
 *
 * <p>A rule is an amount band {@code [min, max]} (inclusive; no max = unbounded) of one currency for single payments,
 * bulk/payroll batches or both ({@code ALL}), or for one non-payment operation type ({@link #OPERATION_TYPES}: loan
 * repayment, credit-line draw or repayment, mandate, collection, virtual account, payment link; {@code ALL} never
 * covers these). A payment uses the matching rule with the highest lower bound (a scope-specific rule beats
 * {@code ALL} at the same bound); when none matches, a default of N approvers in any order. An operation needs
 * approval only when a rule of its type covers it ({@link #covering}); otherwise it runs immediately.
 * It needs {@code required} distinct approvers, never its initiator. {@code ANY_ORDER}: any entitled approver, or only
 * those of the rule's pool when it names approvers. {@code SEQUENTIAL}: exactly the rule's approvers, in that order.
 */
public final class ApprovalPolicy {
    public static final int MAX_APPROVALS = 10;
    /** Payment scopes, covered by {@code ALL} rules. */
    public static final Set<String> PAYMENT_SCOPES = Set.of("SINGLE", "BULK");
    /** Non-payment operation types, covered only by rules naming them. */
    public static final Set<String> OPERATION_TYPES = Set.of("LOAN_REPAYMENT", "CREDIT_LINE_DRAW",
            "CREDIT_LINE_REPAYMENT", "MANDATE", "COLLECTION", "VIRTUAL_ACCOUNT", "PAYMENT_LINK");

    /** One rule of the matrix. */
    public record Rule(UUID id, String appliesTo, String currency, BigDecimal min, BigDecimal max, int required,
                       String order, List<UUID> approvers) {
        public Rule {
            approvers = approvers == null ? List.of() : List.copyOf(approvers);
            appliesTo = appliesTo == null ? "ALL" : appliesTo;
            order = order == null ? "ANY_ORDER" : order;
            min = min == null ? BigDecimal.ZERO : min;
        }

        boolean covers(String scope, String ccy, BigDecimal amount) {
            return currency.equals(ccy) && (appliesTo.equals(scope) || "ALL".equals(appliesTo) && PAYMENT_SCOPES.contains(scope))
                    && amount.compareTo(min) >= 0 && (max == null || amount.compareTo(max) <= 0);
        }
    }

    /** What a payment needs, fixed at submission. */
    public record Plan(UUID ruleId, int required, String order, List<UUID> approvers) {
        public Plan {
            approvers = approvers == null ? List.of() : List.copyOf(approvers);
        }

        public boolean sequential() {
            return "SEQUENTIAL".equals(order);
        }
    }

    private ApprovalPolicy() {}

    /** The plan of a payment: its matching rule, else the default. */
    public static Plan plan(List<Rule> rules, String scope, String currency, BigDecimal amount, int defaultRequired) {
        return covering(rules, scope, currency, amount)
                .orElseGet(() -> new Plan(null, Math.max(1, defaultRequired), "ANY_ORDER", List.of()));
    }

    /** The plan of the matching rule, if any rule covers the scope (payment scope or operation type), currency and amount. */
    public static Optional<Plan> covering(List<Rule> rules, String scope, String currency, BigDecimal amount) {
        return rules.stream().filter(r -> r.covers(scope, currency, amount))
                .max(Comparator.comparing(Rule::min)
                        .thenComparing(r -> "ALL".equals(r.appliesTo()) ? 0 : 1)
                        .thenComparingInt(Rule::required))
                .map(r -> new Plan(r.id(), r.required(), r.order(), r.approvers()));
    }

    /**
     * Why {@code user} may not approve now, or empty when the approval is allowed by the matrix (entitlements and role
     * are checked by the caller). Codes: {@code self_approval_forbidden}, {@code already_decided},
     * {@code not_designated_approver}, {@code out_of_sequence}.
     */
    public static Optional<String> refusal(Plan plan, UUID initiator, List<UUID> decidedSoFar, UUID user) {
        if (user.equals(initiator)) {
            return Optional.of("self_approval_forbidden");
        }
        if (decidedSoFar.contains(user)) {
            return Optional.of("already_decided");
        }
        if (!plan.approvers().isEmpty() && !plan.approvers().contains(user)) {
            return Optional.of("not_designated_approver");
        }
        if (plan.sequential()) {
            int next = decidedSoFar.size();
            if (next >= plan.approvers().size() || !plan.approvers().get(next).equals(user)) {
                return Optional.of("out_of_sequence");
            }
        }
        return Optional.empty();
    }

    /** Whether the user may reject now (any designated, not-yet-decided approver other than the initiator). */
    public static Optional<String> rejectRefusal(Plan plan, UUID initiator, List<UUID> decidedSoFar, UUID user) {
        if (user.equals(initiator)) {
            return Optional.of("self_approval_forbidden");
        }
        if (decidedSoFar.contains(user)) {
            return Optional.of("already_decided");
        }
        if (!plan.approvers().isEmpty() && !plan.approvers().contains(user)) {
            return Optional.of("not_designated_approver");
        }
        return Optional.empty();
    }

    public static boolean complete(Plan plan, int approvals) {
        return approvals >= plan.required();
    }

    /** Why the plan can never be met for this initiator (the initiator is a named approver it cannot do without). */
    public static Optional<String> unsatisfiable(Plan plan, UUID initiator) {
        if (plan.approvers().isEmpty()) {
            return Optional.empty();
        }
        if (plan.sequential() && plan.approvers().subList(0, Math.min(plan.required(), plan.approvers().size()))
                .contains(initiator)) {
            return Optional.of("The approval rule names you as one of its sequential approvers; you cannot approve "
                    + "your own payment");
        }
        long others = plan.approvers().stream().filter(a -> !a.equals(initiator)).distinct().count();
        if (others < plan.required()) {
            return Optional.of("The approval rule's approvers (other than you) are fewer than the " + plan.required()
                    + " approvals it requires");
        }
        return Optional.empty();
    }

    /** Validation problems of a rule (empty when valid). */
    public static List<String> problems(Rule r) {
        List<String> out = new ArrayList<>();
        if (r.required() < 1 || r.required() > MAX_APPROVALS) {
            out.add("required_approvals must be between 1 and " + MAX_APPROVALS);
        }
        if (r.min().signum() < 0) {
            out.add("min_amount must not be negative");
        }
        if (r.max() != null && r.max().compareTo(r.min()) < 0) {
            out.add("max_amount must not be below min_amount");
        }
        if (new HashSet<>(r.approvers()).size() != r.approvers().size()) {
            out.add("approver_ids must be distinct");
        }
        if ("SEQUENTIAL".equals(r.order()) && r.approvers().size() != r.required()) {
            out.add("a SEQUENTIAL rule names exactly required_approvals approvers, in order");
        }
        if (!"SEQUENTIAL".equals(r.order()) && !r.approvers().isEmpty() && r.approvers().size() < r.required()) {
            out.add("the approver pool is smaller than required_approvals");
        }
        return out;
    }

    /** Whether two rules' bands overlap for some payment (same currency, overlapping scope and amounts). */
    public static boolean overlaps(Rule a, Rule b) {
        if (!a.currency().equals(b.currency())) {
            return false;
        }
        boolean scopes = a.appliesTo().equals(b.appliesTo())
                || "ALL".equals(a.appliesTo()) && (PAYMENT_SCOPES.contains(b.appliesTo()))
                || "ALL".equals(b.appliesTo()) && (PAYMENT_SCOPES.contains(a.appliesTo()));
        if (!scopes) {
            return false;
        }
        boolean aBelowB = a.max() != null && a.max().compareTo(b.min()) < 0;
        boolean bBelowA = b.max() != null && b.max().compareTo(a.min()) < 0;
        return !aBelowB && !bBelowA;
    }
}
