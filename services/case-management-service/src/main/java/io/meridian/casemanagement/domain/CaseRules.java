package io.meridian.casemanagement.domain;

import java.util.Map;
import java.util.Set;

/**
 * Lifecycles of AML cases, tasks and SAR/STR reports.
 *
 * <pre>
 * case:  OPEN -> INVESTIGATING (assign) -> ESCALATED (escalate) -> PENDING_APPROVAL (close: disposition proposed)
 *        -> CLOSED (disposition approved by a second user) ; PENDING_APPROVAL -> INVESTIGATING (rejected)
 *        filing a SAR moves an active case to SAR_FILED ; CLOSED -> INVESTIGATING (reopen)
 * task:  OPEN <-> IN_PROGRESS -> DONE (complete) ; OPEN / IN_PROGRESS -> CANCELLED
 * SAR:   DRAFT -> PENDING_APPROVAL -> APPROVED (MLRO, not the preparer) -> SUBMITTED -> ACKNOWLEDGED | REJECTED
 *        REJECTED -> PENDING_APPROVAL (corrected and resubmitted)
 * </pre>
 */
public final class CaseRules {
    private CaseRules() {}

    public static final Set<String> ACTIVE = Set.of("OPEN", "INVESTIGATING", "ESCALATED", "SAR_FILED");

    public static final Map<String, Set<String>> CASE_ACTIONS = Map.of(
            "assign", Set.of("OPEN", "INVESTIGATING", "ESCALATED", "SAR_FILED"),
            "escalate", Set.of("OPEN", "INVESTIGATING", "SAR_FILED"),
            "close", ACTIVE,
            "reopen", Set.of("CLOSED"),
            "link_alert", Set.of("OPEN", "INVESTIGATING", "ESCALATED", "SAR_FILED", "PENDING_APPROVAL"),
            "edit", Set.of("OPEN", "INVESTIGATING", "ESCALATED", "SAR_FILED"),
            "work", ACTIVE);

    public static boolean caseAllows(String action, String status) {
        return CASE_ACTIONS.getOrDefault(action, Set.of()).contains(status);
    }

    /** Status after an assignment: a new case starts being investigated; other states are kept. */
    public static String afterAssign(String status) {
        return "OPEN".equals(status) ? "INVESTIGATING" : status;
    }

    public static final Map<String, Set<String>> TASK_TRANSITIONS = Map.of(
            "OPEN", Set.of("IN_PROGRESS", "CANCELLED"),
            "IN_PROGRESS", Set.of("OPEN", "CANCELLED"),
            "DONE", Set.of(),
            "CANCELLED", Set.of());

    public static boolean taskTransition(String from, String to) {
        return from.equals(to) || TASK_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static final Map<String, Set<String>> SAR_ACTIONS = Map.of(
            "edit", Set.of("DRAFT", "REJECTED"),
            "draft_narrative", Set.of("DRAFT", "REJECTED"),
            "submit_for_approval", Set.of("DRAFT", "REJECTED"),
            "approve", Set.of("PENDING_APPROVAL"),
            "file", Set.of("APPROVED"),
            "acknowledge", Set.of("SUBMITTED"),
            "record_rejection", Set.of("SUBMITTED"),
            "export", Set.of("APPROVED", "SUBMITTED", "ACKNOWLEDGED", "REJECTED"));

    public static boolean sarAllows(String action, String status) {
        return SAR_ACTIONS.getOrDefault(action, Set.of()).contains(status);
    }

    public static final Set<String> FILING_CHANNELS = Set.of("API", "EXPORT", "PORTAL");
    public static final Set<String> EVIDENCE_TYPES = Set.of("STATEMENT", "KYC_DOCUMENT", "SCREENSHOT", "GRAPH_EXPORT",
            "CORRESPONDENCE", "OTHER");
    public static final Set<String> NOTE_VISIBILITY = Set.of("INTERNAL", "RESTRICTED");

    /** Case number prefix per case type. */
    public static String prefix(String caseType) {
        return switch (caseType) {
            case "FRAUD" -> "FRD";
            case "SANCTIONS" -> "SAN";
            case "KYC" -> "KYC";
            case "MULE" -> "MUL";
            case "TERRORIST_FINANCING" -> "TFN";
            case "BRIBERY_CORRUPTION" -> "ABC";
            default -> "AML";
        };
    }

    /** Default investigation deadline (days) by priority. */
    public static int dueDays(String priority) {
        return switch (priority) {
            case "CRITICAL" -> 7;
            case "HIGH" -> 14;
            case "LOW" -> 60;
            default -> 30;
        };
    }
}
