package io.meridian.campaign.domain;

import java.util.Set;

/** State machines of campaigns, offers and offer assignments. */
public final class Lifecycles {
    private Lifecycles() {}

    public static final Set<String> CAMPAIGN_LIVE = Set.of("RUNNING", "PAUSED");

    public static final StateMachine CAMPAIGN = new StateMachine("campaign")
            .on("submit", "DRAFT", "PENDING_APPROVAL")
            .on("approve", "PENDING_APPROVAL", "SCHEDULED")
            .on("reject", "PENDING_APPROVAL", "DRAFT")
            .on("schedule", Set.of("DRAFT", "SCHEDULED"), StateMachine.KEEP)
            .on("build-audience", Set.of("DRAFT", "PENDING_APPROVAL", "SCHEDULED"), StateMachine.KEEP)
            .on("launch", "SCHEDULED", "RUNNING")
            .on("dispatch", "RUNNING", StateMachine.KEEP)
            .on("pause", "RUNNING", "PAUSED")
            .on("resume", "PAUSED", "RUNNING")
            .on("complete", Set.of("RUNNING", "PAUSED"), "COMPLETED")
            .on("cancel", Set.of("DRAFT", "PENDING_APPROVAL", "SCHEDULED", "RUNNING", "PAUSED"), "CANCELLED");

    public static final StateMachine OFFER = new StateMachine("offer")
            .on("activate", Set.of("DRAFT", "PAUSED"), "ACTIVE")
            .on("pause", "ACTIVE", "PAUSED")
            .on("withdraw", Set.of("DRAFT", "ACTIVE", "PAUSED"), "WITHDRAWN")
            .on("expire", Set.of("ACTIVE", "PAUSED"), "EXPIRED");

    /** Assignments still awaiting a customer response. */
    public static final Set<String> ASSIGNMENT_OPEN = Set.of("ASSIGNED", "SHOWN", "CLICKED");

    public static final StateMachine ASSIGNMENT = new StateMachine("offer assignment")
            .on("shown", "ASSIGNED", "SHOWN")
            .on("shown", Set.of("SHOWN", "CLICKED"), StateMachine.KEEP)
            .on("clicked", Set.of("ASSIGNED", "SHOWN"), "CLICKED")
            .on("clicked", "CLICKED", StateMachine.KEEP)
            .on("accept", ASSIGNMENT_OPEN, "ACCEPTED")
            .on("decline", ASSIGNMENT_OPEN, "DECLINED")
            .on("expire", ASSIGNMENT_OPEN, "EXPIRED")
            .on("withdraw", ASSIGNMENT_OPEN, "WITHDRAWN");
}
