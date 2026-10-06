package io.meridian.crm.domain;

import static io.meridian.crm.domain.StateMachine.KEEP;

import java.util.Set;

/** State machines of the servicing entities. */
public final class Lifecycles {
    private Lifecycles() {}

    // ------------------------------------------------------------------ service case

    public static final Set<String> CASE_TERMINAL = Set.of("RESOLVED", "CLOSED", "CANCELLED");
    private static final Set<String> CASE_WORKABLE = Set.of("NEW", "OPEN", "IN_PROGRESS", "PENDING_CUSTOMER",
            "PENDING_INTERNAL", "ESCALATED", "REOPENED");

    public static final StateMachine CASE = new StateMachine("service case")
            .on("assign", Set.of("NEW", "REOPENED"), "OPEN")
            .on("assign", Set.of("OPEN", "IN_PROGRESS", "PENDING_CUSTOMER", "PENDING_INTERNAL", "ESCALATED"), KEEP)
            .on("start", Set.of("NEW", "OPEN", "REOPENED", "ESCALATED", "PENDING_INTERNAL"), "IN_PROGRESS")
            .on("pending-customer", Set.of("NEW", "OPEN", "IN_PROGRESS", "ESCALATED", "REOPENED", "PENDING_INTERNAL"),
                    "PENDING_CUSTOMER")
            .on("customer-responded", "PENDING_CUSTOMER", "IN_PROGRESS")
            .on("escalate", CASE_WORKABLE, "ESCALATED")
            .on("resolve", CASE_WORKABLE, "RESOLVED")
            .on("close", "RESOLVED", "CLOSED")
            .on("reopen", Set.of("RESOLVED", "CLOSED"), "REOPENED")
            .on("cancel", CASE_WORKABLE, "CANCELLED")
            .on("comment", CASE_WORKABLE, KEEP)
            .on("comment", "RESOLVED", KEEP)
            .on("convert-to-complaint", CASE_WORKABLE, KEEP)
            .on("convert-to-complaint", Set.of("RESOLVED", "CLOSED"), KEEP);

    // ------------------------------------------------------------------ complaint

    /** Statuses in which the firm still owes the complainant a final response. */
    public static final Set<String> COMPLAINT_AWAITING_RESPONSE =
            Set.of("RECEIVED", "ACKNOWLEDGED", "INVESTIGATING", "RESOLUTION_PROPOSED");

    public static final StateMachine COMPLAINT = new StateMachine("complaint")
            .on("acknowledge", "RECEIVED", "ACKNOWLEDGED")
            .on("assign", Set.of("RECEIVED", "ACKNOWLEDGED", "INVESTIGATING", "RESOLUTION_PROPOSED", "RESOLVED",
                    "REJECTED", "ESCALATED_TO_OMBUDSMAN"), KEEP)
            .on("investigate", "ACKNOWLEDGED", "INVESTIGATING")
            .on("propose-resolution", Set.of("INVESTIGATING", "RESOLUTION_PROPOSED"), "RESOLUTION_PROPOSED")
            .on("final-response", "RESOLUTION_PROPOSED", "RESOLVED")
            .on("escalate", Set.of("RECEIVED", "ACKNOWLEDGED", "INVESTIGATING", "RESOLUTION_PROPOSED", "RESOLVED",
                    "REJECTED"), "ESCALATED_TO_OMBUDSMAN")
            .on("withdraw", COMPLAINT_AWAITING_RESPONSE, "CLOSED")
            .on("close", Set.of("RESOLVED", "REJECTED", "ESCALATED_TO_OMBUDSMAN"), "CLOSED")
            .on("redress", Set.of("ACKNOWLEDGED", "INVESTIGATING", "RESOLUTION_PROPOSED", "RESOLVED", "REJECTED",
                    "ESCALATED_TO_OMBUDSMAN"), KEEP);

    // ------------------------------------------------------------------ redress

    public static final Set<String> MONETARY_REDRESS = Set.of("REFUND", "FEE_REVERSAL", "INTEREST_ADJUSTMENT", "GOODWILL_PAYMENT");

    public static final StateMachine REDRESS = new StateMachine("redress")
            .on("approve", "PROPOSED", "APPROVED")
            .on("reject", Set.of("PROPOSED", "APPROVED", "FAILED"), "REJECTED")
            .on("pay", Set.of("APPROVED", "FAILED"), "PAID");

    // ------------------------------------------------------------------ work item

    public static final Set<String> ITEM_OPEN = Set.of("QUEUED", "ASSIGNED", "IN_PROGRESS");

    public static final StateMachine WORK_ITEM = new StateMachine("work item")
            .on("assign", ITEM_OPEN, "ASSIGNED")
            .on("start", "ASSIGNED", "IN_PROGRESS")
            .on("release", Set.of("ASSIGNED", "IN_PROGRESS"), "QUEUED")
            .on("complete", Set.of("ASSIGNED", "IN_PROGRESS"), "DONE")
            .on("cancel", ITEM_OPEN, "CANCELLED");

    // ------------------------------------------------------------------ knowledge article

    public static final StateMachine ARTICLE = new StateMachine("knowledge article")
            .on("submit-review", "DRAFT", "IN_REVIEW")
            .on("publish", "IN_REVIEW", "PUBLISHED")
            .on("archive", Set.of("DRAFT", "IN_REVIEW", "PUBLISHED"), "ARCHIVED")
            .on("new-version", Set.of("PUBLISHED", "ARCHIVED"), "DRAFT")
            .on("edit", "DRAFT", KEEP)
            .on("feedback", "PUBLISHED", KEEP);
}
