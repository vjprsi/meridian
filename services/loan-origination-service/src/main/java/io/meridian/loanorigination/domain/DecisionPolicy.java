package io.meridian.loanorigination.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Automatic credit decision rules. Every rule reports PASS, REFER or DECLINE with a stable code; the outcome is the
 * most severe one (DECLINE > REFER > COUNTER_OFFER > APPROVE). An unaffordable request becomes a COUNTER_OFFER for the
 * largest affordable amount (rounded down to the counter-offer step) when that is still within the product minimum.
 */
public final class DecisionPolicy {
    private DecisionPolicy() {}

    public enum Severity { PASS, REFER, DECLINE }

    public record Rule(String code, Severity severity, String detail) {}

    /** Facts about the application gathered by the application layer. */
    public record Facts(String loanType, Integer age, String bureauStatus, Integer worstDpd24m,
                        Integer delinquentAccounts, Integer inquiries6m, String cutoffOutcome, String affordability,
                        BigDecimal maxAffordableAmount, BigDecimal requestedAmount, BigDecimal productMinAmount,
                        BigDecimal counterOfferStep) {}

    public record Outcome(String outcome, BigDecimal approvedAmount, List<Rule> rules) {
        public List<Rule> failed() {
            return rules.stream().filter(r -> r.severity() != Severity.PASS).toList();
        }
    }

    public static Outcome evaluate(Facts f) {
        List<Rule> rules = new ArrayList<>();
        if (f.age() != null) {
            rules.add(f.age() < 18 ? new Rule("MINIMUM_AGE", Severity.DECLINE, "Applicant is younger than 18")
                    : f.age() > 80 ? new Rule("MAXIMUM_AGE", Severity.REFER, "Applicant is older than 80")
                    : new Rule("AGE", Severity.PASS, "Age within policy"));
        }
        boolean nano = "NANO".equals(f.loanType());
        if (f.bureauStatus() == null) {
            rules.add(new Rule("BUREAU_REPORT", nano ? Severity.PASS : Severity.REFER,
                    nano ? "Nano-loan scored on wallet data" : "No bureau report available"));
        } else if ("NO_HIT".equals(f.bureauStatus())) {
            rules.add(new Rule("NO_CREDIT_HISTORY", nano ? Severity.PASS : Severity.DECLINE,
                    "No record at the credit bureau"));
        } else if ("FAILED".equals(f.bureauStatus())) {
            rules.add(new Rule("BUREAU_UNAVAILABLE", Severity.REFER, "Bureau report failed"));
        } else {
            rules.add(new Rule("BUREAU_REPORT", Severity.PASS, "Bureau report received"));
        }
        int worst = f.worstDpd24m() == null ? 0 : f.worstDpd24m();
        rules.add(worst >= 90 ? new Rule("SERIOUS_DELINQUENCY", Severity.DECLINE, "90+ days past due in the last 24 months")
                : worst >= 30 ? new Rule("RECENT_DELINQUENCY", Severity.REFER, "30+ days past due in the last 24 months")
                : new Rule("DELINQUENCY_HISTORY", Severity.PASS, "No material delinquency"));
        int delinquent = f.delinquentAccounts() == null ? 0 : f.delinquentAccounts();
        rules.add(delinquent >= 2 ? new Rule("MULTIPLE_DELINQUENT_ACCOUNTS", Severity.DECLINE, delinquent + " accounts in arrears")
                : delinquent == 1 ? new Rule("DELINQUENT_ACCOUNT", Severity.REFER, "One account in arrears")
                : new Rule("ACCOUNTS_IN_ARREARS", Severity.PASS, "No account in arrears"));
        int inq = f.inquiries6m() == null ? 0 : f.inquiries6m();
        rules.add(inq >= 6 ? new Rule("CREDIT_SEEKING", Severity.REFER, inq + " credit inquiries in 6 months")
                : new Rule("CREDIT_INQUIRIES", Severity.PASS, "Inquiries within policy"));
        if (f.cutoffOutcome() != null) {
            rules.add(switch (f.cutoffOutcome()) {
                case "DECLINE" -> new Rule("SCORE_BELOW_CUTOFF", Severity.DECLINE, "Score below the decline cut-off");
                case "REFER" -> new Rule("SCORE_IN_REFER_BAND", Severity.REFER, "Score in the referral band");
                default -> new Rule("SCORE_CUTOFF", Severity.PASS, "Score above the approval cut-off");
            });
        }
        BigDecimal counter = null;
        if ("FAIL".equals(f.affordability())) {
            BigDecimal step = f.counterOfferStep() == null || f.counterOfferStep().signum() <= 0
                    ? BigDecimal.valueOf(100) : f.counterOfferStep();
            BigDecimal max = f.maxAffordableAmount() == null ? BigDecimal.ZERO
                    : f.maxAffordableAmount().divide(step, 0, RoundingMode.DOWN).multiply(step);
            BigDecimal min = f.productMinAmount() == null ? BigDecimal.ONE : f.productMinAmount();
            if (max.compareTo(min) >= 0 && max.compareTo(f.requestedAmount()) < 0) {
                counter = max;
                rules.add(new Rule("UNAFFORDABLE_COUNTER_OFFER", Severity.PASS,
                        "Requested amount unaffordable; " + max.toPlainString() + " affordable"));
            } else {
                rules.add(new Rule("UNAFFORDABLE", Severity.DECLINE, "Repayments are not affordable"));
            }
        } else if ("MARGINAL".equals(f.affordability())) {
            rules.add(new Rule("MARGINAL_AFFORDABILITY", Severity.REFER, "Affordability is marginal"));
        } else if (f.affordability() != null) {
            rules.add(new Rule("AFFORDABILITY", Severity.PASS, "Affordable"));
        }
        boolean decline = rules.stream().anyMatch(r -> r.severity() == Severity.DECLINE);
        boolean refer = rules.stream().anyMatch(r -> r.severity() == Severity.REFER);
        String outcome = decline ? "DECLINED" : refer ? "REFER" : counter != null ? "COUNTER_OFFER" : "APPROVED";
        BigDecimal amount = switch (outcome) {
            case "COUNTER_OFFER" -> counter;
            case "APPROVED" -> f.requestedAmount();
            default -> null;
        };
        return new Outcome(outcome, amount, List.copyOf(rules));
    }
}
