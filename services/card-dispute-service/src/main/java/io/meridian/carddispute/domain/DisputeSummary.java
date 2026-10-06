package io.meridian.carddispute.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Deterministic copilot summary (template, no model call): the same dispute facts always give the same text, so
 * the summary is reproducible and auditable.
 */
public final class DisputeSummary {
    private DisputeSummary() {}

    public record Facts(String category, String scheme, String reasonCode, String stage, String status,
                        BigDecimal disputedAmount, String currency, BigDecimal provisionalCredit,
                        boolean provisionalReversed, LocalDate transactionDate, LocalDate stageDeadline,
                        LocalDate regulatoryDeadline, int evidenceCount, int submittedEvidence,
                        String customerDescription) {}

    public static String suggestedCode(Facts f) {
        return ReasonCodes.suggest(f.category(), f.scheme());
    }

    public static String render(Facts f) {
        String suggested = suggestedCode(f);
        StringBuilder s = new StringBuilder();
        s.append("Dispute summary: ").append(human(f.category())).append(" dispute on a ").append(f.scheme())
                .append(" card for ").append(money(f.disputedAmount())).append(' ').append(f.currency());
        if (f.transactionDate() != null) {
            s.append(" (transaction ").append(f.transactionDate()).append(')');
        }
        s.append(". Stage ").append(f.stage()).append(", status ").append(f.status()).append(".\n");
        s.append("Suggested reason code: ").append(suggested == null ? "n/a" : suggested)
                .append(suggested == null ? "" : " - " + ReasonCodes.describe(suggested));
        if (f.reasonCode() != null && !f.reasonCode().equals(suggested)) {
            s.append(" (filed as ").append(f.reasonCode()).append(" - ").append(ReasonCodes.describe(f.reasonCode()))
                    .append(')');
        }
        s.append(".\n");
        s.append("Provisional credit: ");
        if (f.provisionalCredit() == null || f.provisionalCredit().signum() == 0) {
            s.append("none");
        } else {
            s.append(money(f.provisionalCredit())).append(' ').append(f.currency())
                    .append(f.provisionalReversed() ? " (reversed)" : " (outstanding)");
        }
        s.append(". Evidence: ").append(f.evidenceCount()).append(" item(s), ").append(f.submittedEvidence())
                .append(" submitted to the scheme.\n");
        if (f.customerDescription() != null && !f.customerDescription().isBlank()) {
            String d = f.customerDescription().strip().replaceAll("\\s+", " ");
            s.append("Cardholder says: \"").append(d.length() > 200 ? d.substring(0, 200) + "..." : d).append("\"\n");
        }
        s.append("Next step: ").append(nextStep(f)).append('.');
        if (f.stageDeadline() != null) {
            s.append(" Stage deadline ").append(f.stageDeadline()).append('.');
        }
        if (f.regulatoryDeadline() != null) {
            s.append(" Regulatory deadline ").append(f.regulatoryDeadline()).append('.');
        }
        return s.toString();
    }

    static String nextStep(Facts f) {
        return switch (f.stage()) {
            case "INQUIRY" -> f.evidenceCount() == 0
                    ? "collect cardholder evidence, then submit the chargeback"
                    : "submit the chargeback with the evidence on file";
            case "RETRIEVAL" -> "wait for the acquirer's retrieval response, then submit the chargeback";
            case "FIRST_CHARGEBACK" -> "wait for the merchant's representment or close as won when the window lapses";
            case "REPRESENTMENT" -> "review the representment: accept it or escalate to pre-arbitration";
            case "PRE_ARBITRATION" -> "await the acquirer's response or escalate to arbitration";
            case "ARBITRATION" -> "await the scheme's arbitration ruling and close with the outcome";
            default -> "none (dispute closed)";
        };
    }

    private static String human(String category) {
        return category == null ? "unknown" : category.toLowerCase().replace('_', ' ');
    }

    private static String money(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
