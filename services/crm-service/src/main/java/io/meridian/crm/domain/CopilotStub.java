package io.meridian.crm.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * Deterministic stand-in for the servicing LLM: builds summaries, drafts and answers only from the grounding records
 * it is given (no external call), and cites every record it used. Same context in, same text out.
 */
public final class CopilotStub {
    public static final String MODEL = "meridian-copilot-stub-1";

    private CopilotStub() {}

    /** Grounding records: each list holds API-shaped rows (newest first). */
    public record Context(JsonNode serviceCase, JsonNode complaint, List<JsonNode> cases, List<JsonNode> complaints,
                          List<JsonNode> comments, List<JsonNode> interactions, List<JsonNode> notes,
                          List<JsonNode> timeline, List<JsonNode> articles, List<JsonNode> redress) {}

    public record Citation(String type, String id, String title) {}

    public record Output(String text, List<Citation> citations) {}

    public static Output run(String task, String prompt, String language, Context c) {
        List<Citation> cites = new ArrayList<>();
        String text = switch (task) {
            case "SUMMARISE_CUSTOMER" -> summariseCustomer(c, cites);
            case "SUMMARISE_CASE" -> summariseCase(c, cites);
            case "DRAFT_REPLY" -> draftReply(c, cites);
            case "CLASSIFY_CASE" -> classify(c, prompt, cites);
            case "SUGGEST_NEXT_ACTION" -> nextAction(c, cites);
            case "ANSWER_QUESTION" -> answer(prompt, c, cites);
            case "DRAFT_COMPLAINT_RESPONSE" -> complaintResponse(c, cites);
            case "TRANSLATE" -> "[" + language + "] " + prompt;
            default -> throw new IllegalArgumentException("unknown task " + task);
        };
        return new Output(text, cites);
    }

    private static String summariseCustomer(Context c, List<Citation> cites) {
        StringBuilder sb = new StringBuilder();
        long openCases = c.cases().stream().filter(x -> !Lifecycles.CASE_TERMINAL.contains(s(x, "status"))).count();
        long openComplaints = c.complaints().stream().filter(x -> !"CLOSED".equals(s(x, "status"))).count();
        sb.append("Customer overview: ").append(c.cases().size()).append(" case(s) (").append(openCases).append(" open), ")
                .append(c.complaints().size()).append(" complaint(s) (").append(openComplaints).append(" open), ")
                .append(c.interactions().size()).append(" recent interaction(s).");
        for (JsonNode n : c.notes()) {
            sb.append("\nAlert: [").append(or(s(n, "category"), "GENERAL")).append("] ").append(first(s(n, "body"), 160));
            cites.add(new Citation("customer_note", s(n, "id"), or(s(n, "category"), "note")));
        }
        c.cases().stream().filter(x -> !Lifecycles.CASE_TERMINAL.contains(s(x, "status"))).limit(3).forEach(x -> {
            sb.append("\nOpen case ").append(s(x, "case_number")).append(" (").append(s(x, "status")).append(", ")
                    .append(s(x, "priority")).append("): ").append(s(x, "subject"));
            if (x.path("sla_breached").asBoolean(false)) {
                sb.append(" - SLA breached");
            }
            cites.add(new Citation("service_case", s(x, "id"), s(x, "case_number")));
        });
        c.complaints().stream().filter(x -> !"CLOSED".equals(s(x, "status"))).limit(3).forEach(x -> {
            sb.append("\nOpen complaint ").append(s(x, "complaint_number")).append(" (").append(s(x, "status"))
                    .append("), final response due ").append(day(s(x, "final_response_due_at"))).append(": ").append(s(x, "summary"));
            cites.add(new Citation("complaint", s(x, "id"), s(x, "complaint_number")));
        });
        if (!c.interactions().isEmpty()) {
            JsonNode last = c.interactions().getFirst();
            sb.append("\nLast contact ").append(day(s(last, "started_at"))).append(" via ").append(s(last, "channel"))
                    .append(" (sentiment ").append(or(s(last, "sentiment"), "NEUTRAL")).append(")");
            if (s(last, "subject") != null) {
                sb.append(": ").append(s(last, "subject"));
            }
            cites.add(new Citation("interaction", s(last, "id"), s(last, "interaction_type")));
        }
        long negative = c.interactions().stream().filter(i -> s(i, "sentiment") != null && s(i, "sentiment").contains("NEGATIVE")).count();
        if (negative * 2 > c.interactions().size() && negative > 0) {
            sb.append("\nCaution: most recent contacts were negative.");
        }
        return sb.toString();
    }

    private static String summariseCase(Context c, List<Citation> cites) {
        JsonNode x = c.serviceCase();
        cites.add(new Citation("service_case", s(x, "id"), s(x, "case_number")));
        StringBuilder sb = new StringBuilder("Case ").append(s(x, "case_number")).append(" - ").append(s(x, "subject"))
                .append("\nType ").append(s(x, "case_type")).append(", priority ").append(s(x, "priority"))
                .append(", status ").append(s(x, "status")).append(", raised via ").append(s(x, "channel")).append('.');
        if (s(x, "resolution_due_at") != null) {
            sb.append("\nResolution due ").append(day(s(x, "resolution_due_at")))
                    .append(x.path("sla_breached").asBoolean(false) ? " (SLA breached)" : "").append('.');
        }
        if (s(x, "description") != null) {
            sb.append("\nCustomer said: ").append(first(s(x, "description"), 240));
        }
        sb.append("\n").append(c.comments().size()).append(" comment(s).");
        if (!c.comments().isEmpty()) {
            JsonNode last = c.comments().getFirst();
            sb.append(" Latest (").append(s(last, "author_type")).append("): ").append(first(s(last, "body"), 200));
            cites.add(new Citation("case_comment", s(last, "id"), "latest comment"));
        }
        if (s(x, "resolution_summary") != null) {
            sb.append("\nResolution: ").append(s(x, "resolution_summary"));
        }
        return sb.toString();
    }

    private static String draftReply(Context c, List<Citation> cites) {
        JsonNode x = c.serviceCase() != null ? c.serviceCase() : c.complaint();
        String ref = c.serviceCase() != null ? s(x, "case_number") : s(x, "complaint_number");
        String topic = c.serviceCase() != null ? s(x, "subject") : s(x, "summary");
        cites.add(new Citation(c.serviceCase() != null ? "service_case" : "complaint", s(x, "id"), ref));
        StringBuilder sb = new StringBuilder("Dear customer,\n\nThank you for contacting us about \"").append(topic)
                .append("\" (reference ").append(ref).append(").");
        String status = s(x, "status");
        if ("RESOLVED".equals(status) || "CLOSED".equals(status)) {
            sb.append(" We have resolved this");
            if (s(x, "resolution_summary") != null) {
                sb.append(": ").append(s(x, "resolution_summary"));
            }
            sb.append('.');
        } else {
            sb.append(" We are looking into it");
            if (s(x, "resolution_due_at") != null) {
                sb.append(" and aim to come back to you by ").append(day(s(x, "resolution_due_at")));
            }
            sb.append('.');
        }
        if (!c.articles().isEmpty()) {
            JsonNode a = c.articles().getFirst();
            sb.append("\n\nYou may find this helpful: ").append(s(a, "title")).append('.');
            cites.add(new Citation("knowledge_article", s(a, "id"), s(a, "title")));
        }
        return sb.append("\n\nKind regards,\nCustomer Service").toString();
    }

    private static String classify(Context c, String prompt, List<Citation> cites) {
        String text = prompt;
        if (c.serviceCase() != null) {
            text = or(s(c.serviceCase(), "subject"), "") + " " + or(s(c.serviceCase(), "description"), "");
            cites.add(new Citation("service_case", s(c.serviceCase(), "id"), s(c.serviceCase(), "case_number")));
        }
        String intent = TextAnalysis.intent(text);
        String sentiment = TextAnalysis.sentiment(text);
        return "intent=" + intent + "\ncase_type=" + TextAnalysis.caseType(intent) + "\npriority="
                + TextAnalysis.priority(intent, sentiment) + "\nsentiment=" + sentiment
                + ("COMPLAINT".equals(intent) ? "\nnote=expression of dissatisfaction: consider raising a formal complaint" : "");
    }

    private static String nextAction(Context c, List<Citation> cites) {
        JsonNode x = c.serviceCase() != null ? c.serviceCase() : c.complaint();
        boolean isCase = c.serviceCase() != null;
        cites.add(new Citation(isCase ? "service_case" : "complaint", s(x, "id"),
                isCase ? s(x, "case_number") : s(x, "complaint_number")));
        String status = s(x, "status");
        List<String> steps = new ArrayList<>();
        if (isCase) {
            if (s(x, "assigned_to") == null && !Lifecycles.CASE_TERMINAL.contains(status)) {
                steps.add("Assign the case to an agent.");
            }
            if (s(x, "first_responded_at") == null && !Lifecycles.CASE_TERMINAL.contains(status)) {
                steps.add("Send a first response to the customer (due " + day(s(x, "first_response_due_at")) + ").");
            }
            if (x.path("sla_breached").asBoolean(false)) {
                steps.add("SLA is breached: escalate to a supervisor queue.");
            }
            if ("PENDING_CUSTOMER".equals(status)) {
                steps.add("Waiting for the customer: send a reminder if there is no reply within 5 business days.");
            }
            if ("RESOLVED".equals(status)) {
                steps.add("Confirm the resolution with the customer and close the case with a CSAT score.");
            }
            String sentiment = c.interactions().isEmpty() ? null : s(c.interactions().getFirst(), "sentiment");
            if (sentiment != null && sentiment.contains("NEGATIVE") && x.path("complaint_id").isNull()) {
                steps.add("Customer sentiment is negative: check whether this should be raised as a formal complaint.");
            }
        } else {
            switch (status) {
                case "RECEIVED" -> steps.add("Acknowledge the complaint (due " + day(s(x, "acknowledgement_due_at")) + ").");
                case "ACKNOWLEDGED" -> steps.add("Start the investigation and gather evidence.");
                case "INVESTIGATING" -> steps.add("Propose an outcome with its root cause and any redress.");
                case "RESOLUTION_PROPOSED" -> steps.add("Issue the final response before " + day(s(x, "final_response_due_at")) + ".");
                case "RESOLVED", "REJECTED" -> steps.add("Pay any approved redress, then close the complaint.");
                default -> steps.add("No action required.");
            }
        }
        if (steps.isEmpty()) {
            steps.add("Continue working the case; no SLA risk detected.");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            sb.append(i + 1).append(". ").append(steps.get(i)).append(i < steps.size() - 1 ? "\n" : "");
        }
        return sb.toString();
    }

    private static String answer(String prompt, Context c, List<Citation> cites) {
        if (c.articles().isEmpty()) {
            return "No published knowledge article answers this question. Escalate to a specialist or raise a content request.";
        }
        JsonNode a = c.articles().getFirst();
        cites.add(new Citation("knowledge_article", s(a, "id"), s(a, "title")));
        return "According to \"" + s(a, "title") + "\": " + first(s(a, "body"), 400);
    }

    private static String complaintResponse(Context c, List<Citation> cites) {
        JsonNode x = c.complaint();
        cites.add(new Citation("complaint", s(x, "id"), s(x, "complaint_number")));
        String outcome = or(s(x, "outcome"), "PENDING");
        StringBuilder sb = new StringBuilder("Final response - complaint ").append(s(x, "complaint_number"))
                .append("\n\nDear customer,\n\nThank you for raising your complaint about \"").append(s(x, "summary"))
                .append("\", received on ").append(day(s(x, "received_at"))).append(".\n\n");
        switch (outcome) {
            case "UPHELD" -> sb.append("Having investigated, we uphold your complaint.");
            case "PARTIALLY_UPHELD" -> sb.append("Having investigated, we uphold your complaint in part.");
            case "NOT_UPHELD" -> sb.append("Having investigated, we do not uphold your complaint.");
            default -> sb.append("Our investigation is still in progress.");
        }
        if (s(x, "root_cause") != null) {
            sb.append(" What went wrong: ").append(s(x, "root_cause")).append('.');
        }
        if (s(x, "resolution_summary") != null) {
            sb.append("\n\n").append(s(x, "resolution_summary"));
        }
        for (JsonNode r : c.redress()) {
            if (!"REJECTED".equals(s(r, "status"))) {
                sb.append("\n\nTo put things right: ").append(s(r, "redress_type").replace('_', ' ').toLowerCase(Locale.ROOT));
                if (s(r, "amount") != null) {
                    sb.append(" of ").append(s(r, "amount")).append(' ').append(s(r, "currency"));
                }
                sb.append('.');
                cites.add(new Citation("complaint_redress", s(r, "id"), s(r, "redress_type")));
            }
        }
        sb.append("\n\nIf you are unhappy with this response you may refer your complaint to ")
                .append(RegulatoryTimelines.ombudsman(s(x, "country")))
                .append(" within six months of the date of this letter.\n\nYours sincerely,\nComplaints Team");
        return sb.toString();
    }

    private static String s(JsonNode n, String f) {
        if (n == null) {
            return null;
        }
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static String or(String v, String dflt) {
        return v == null ? dflt : v;
    }

    private static String day(String ts) {
        return ts == null ? "n/a" : ts.substring(0, Math.min(10, ts.length()));
    }

    private static String first(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 3) + "...";
    }
}
