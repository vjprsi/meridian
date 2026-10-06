package io.meridian.casemanagement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic SAR/STR narrative and case summary from case facts (who, what, when, where, why, how). The same facts
 * always produce the same text, so drafts are reproducible and auditable; investigators edit the draft before filing.
 */
public final class NarrativeBuilder {
    private NarrativeBuilder() {}

    public record Alert(String id, String source, String summary, String addedAt) {}

    public record Txn(String id, String date, BigDecimal amount, String currency, String from, String to, String description) {}

    public record Subject(String name, String type, String role, String country, String idNumber) {}

    public record Facts(String caseNumber, String caseType, String title, String description, String customerId,
                        BigDecimal totalAmount, String currency, String openedAt, String activityStart,
                        String activityEnd, String reportType, String jurisdiction, String regulator, List<Alert> alerts,
                        List<Subject> subjects, List<Txn> transactions, int notes, int evidence, List<String> actions) {}

    public record Citation(String ref, String text) {}

    public record Summary(String text, List<Citation> citations) {}

    public static String narrative(Facts f) {
        StringBuilder sb = new StringBuilder();
        String type = f.reportType() == null ? "SAR" : f.reportType();
        sb.append(type).append(" for case ").append(f.caseNumber()).append(" (").append(human(f.caseType()))
                .append(") filed with ").append(f.regulator()).append(" (").append(f.jurisdiction()).append(").\n\n");
        sb.append("WHO: ");
        if (f.subjects().isEmpty()) {
            sb.append(f.customerId() == null ? "The subject is not yet identified." : "The bank's customer " + f.customerId() + ".");
        } else {
            List<String> who = new ArrayList<>();
            for (Subject s : f.subjects()) {
                StringBuilder w = new StringBuilder(s.name());
                List<String> attrs = new ArrayList<>();
                if (s.type() != null) {
                    attrs.add(s.type().toLowerCase(java.util.Locale.ROOT));
                }
                if (s.role() != null) {
                    attrs.add(s.role().toLowerCase(java.util.Locale.ROOT));
                }
                if (s.country() != null) {
                    attrs.add("country " + s.country());
                }
                if (s.idNumber() != null) {
                    attrs.add("id " + s.idNumber());
                }
                if (!attrs.isEmpty()) {
                    w.append(" (").append(String.join(", ", attrs)).append(')');
                }
                who.add(w.toString());
            }
            sb.append(String.join("; ", who)).append('.');
        }
        sb.append("\n\nWHAT: ");
        BigDecimal total = f.totalAmount();
        if ((total == null || total.signum() == 0) && !f.transactions().isEmpty()) {
            total = f.transactions().stream().map(Txn::amount).filter(a -> a != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        sb.append(f.transactions().size()).append(" transaction(s)");
        if (total != null && total.signum() > 0) {
            sb.append(" totalling ").append(money(total));
            if (f.currency() != null) {
                sb.append(' ').append(f.currency());
            }
        }
        sb.append(" are reported as suspicious");
        if (f.title() != null) {
            sb.append(": ").append(f.title());
        }
        sb.append('.');
        sb.append("\n\nWHEN: ");
        String start = f.activityStart() != null ? f.activityStart() : first(f.transactions());
        String end = f.activityEnd() != null ? f.activityEnd() : last(f.transactions());
        if (start != null && end != null) {
            sb.append("The activity took place between ").append(start).append(" and ").append(end).append('.');
        } else {
            sb.append("The activity period is under review.");
        }
        sb.append(" The case was opened on ").append(f.openedAt() == null ? "an unrecorded date" : f.openedAt().substring(0, Math.min(10, f.openedAt().length()))).append('.');
        Set<String> places = new LinkedHashSet<>();
        f.subjects().forEach(s -> {
            if (s.country() != null) {
                places.add(s.country());
            }
        });
        sb.append("\n\nWHERE: ");
        sb.append(places.isEmpty() ? "Accounts held with the reporting institution." : "Parties located in " + String.join(", ", places) + '.');
        sb.append("\n\nWHY: ");
        if (f.alerts().isEmpty()) {
            sb.append("The activity was referred for investigation.");
        } else {
            Map<String, Integer> bySource = new LinkedHashMap<>();
            f.alerts().forEach(a -> bySource.merge(human(a.source()), 1, Integer::sum));
            List<String> parts = new ArrayList<>();
            bySource.forEach((k, v) -> parts.add(v + " " + k + (v > 1 ? "s" : "")));
            sb.append("The case consolidates ").append(String.join(", ", parts)).append(". ");
            List<String> summaries = f.alerts().stream().map(Alert::summary).filter(s -> s != null && !s.isBlank()).limit(5).toList();
            if (!summaries.isEmpty()) {
                sb.append("Indicators: ").append(String.join("; ", summaries)).append('.');
            }
        }
        if (f.description() != null && !f.description().isBlank()) {
            sb.append(" Investigation scope: ").append(f.description().trim()).append(f.description().trim().endsWith(".") ? "" : ".");
        }
        sb.append("\n\nHOW: ");
        if (f.transactions().isEmpty()) {
            sb.append("Transaction details are attached to the case evidence.");
        } else {
            List<String> lines = new ArrayList<>();
            for (Txn t : f.transactions().stream().limit(10).toList()) {
                lines.add((t.date() == null ? "" : t.date() + ": ") + (t.amount() == null ? "" : money(t.amount()) + " " + nz(t.currency()))
                        + (t.from() != null ? " from " + t.from() : "") + (t.to() != null ? " to " + t.to() : "")
                        + (t.description() != null ? " (" + t.description() + ")" : ""));
            }
            sb.append(String.join("; ", lines));
            if (f.transactions().size() > 10) {
                sb.append("; and ").append(f.transactions().size() - 10).append(" further transaction(s)");
            }
            sb.append('.');
        }
        sb.append("\n\nACTIONS: ");
        sb.append(f.actions().isEmpty() ? "Enhanced monitoring is in place pending the authorities' response."
                : String.join("; ", f.actions()) + '.');
        sb.append(" Supporting evidence: ").append(f.evidence()).append(" document(s); ").append(f.notes())
                .append(" investigator note(s).");
        return sb.toString();
    }

    /** Short case summary with a citation per fact used. */
    public static Summary summary(Facts f, Map<String, String> citationRefs) {
        List<Citation> citations = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        sb.append(human(f.caseType())).append(" case ").append(f.caseNumber()).append(": ").append(f.title()).append(". [1]");
        citations.add(new Citation(citationRefs.getOrDefault("case", "aml_case"), "Case record"));
        int n = 2;
        for (Alert a : f.alerts()) {
            sb.append(' ').append(capital(human(a.source()))).append(a.summary() == null ? "" : ": " + a.summary()).append(". [").append(n).append(']');
            citations.add(new Citation("aml_case_alert:" + a.id(), human(a.source())));
            n++;
        }
        if (f.totalAmount() != null && f.totalAmount().signum() > 0) {
            sb.append(" Total suspicious amount ").append(money(f.totalAmount())).append(' ').append(nz(f.currency())).append('.');
        }
        sb.append(' ').append(f.evidence()).append(" evidence item(s) and ").append(f.notes()).append(" note(s) on file.");
        if (!f.actions().isEmpty()) {
            sb.append(" Latest disposition: ").append(String.join("; ", f.actions())).append('.');
        }
        return new Summary(sb.toString(), citations);
    }

    static String human(String code) {
        return code == null ? "" : code.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String first(List<Txn> t) {
        return t.stream().map(Txn::date).filter(d -> d != null).sorted().findFirst().orElse(null);
    }

    private static String last(List<Txn> t) {
        return t.stream().map(Txn::date).filter(d -> d != null).sorted(java.util.Comparator.reverseOrder()).findFirst().orElse(null);
    }
}
