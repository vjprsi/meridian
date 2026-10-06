package io.meridian.productcatalog.domain;

import io.meridian.common.error.ApiException;
import java.util.Objects;
import java.util.Set;

/** The lifecycles of the Product Studio aggregates. */
public final class Lifecycles {
    private Lifecycles() {}

    /**
     * Product (catalogue entry): DRAFT -activate (first publish)-> ACTIVE -suspend-> SUSPENDED -reactivate-> ACTIVE;
     * any non-retired status -retire-> RETIRED (terminal).
     */
    public static final StateMachine PRODUCT = StateMachine.of("product")
            .on("activate", "DRAFT", "ACTIVE")
            .on("suspend", "ACTIVE", "SUSPENDED")
            .on("reactivate", "SUSPENDED", "ACTIVE")
            .on("retire", Set.of("DRAFT", "ACTIVE", "SUSPENDED"), "RETIRED")
            .build();

    /**
     * Product version (maker-checker): DRAFT -submit-> PENDING_APPROVAL -approve-> APPROVED -publish-> PUBLISHED
     * -supersede-> SUPERSEDED; PENDING_APPROVAL -reject-> REJECTED; PUBLISHED/SUPERSEDED -retire-> RETIRED.
     */
    public static final StateMachine VERSION = StateMachine.of("product version")
            .on("submit", "DRAFT", "PENDING_APPROVAL")
            .on("approve", "PENDING_APPROVAL", "APPROVED")
            .on("reject", "PENDING_APPROVAL", "REJECTED")
            .on("publish", "APPROVED", "PUBLISHED")
            .on("supersede", "PUBLISHED", "SUPERSEDED")
            .on("retire", Set.of("PUBLISHED", "SUPERSEDED"), "RETIRED")
            .build();

    /** Version statuses whose content is still in flight (only one per product at a time). */
    public static final Set<String> VERSION_IN_FLIGHT = Set.of("DRAFT", "PENDING_APPROVAL", "APPROVED");

    /** Version statuses that may serve as the source of an account migration. */
    public static final Set<String> VERSION_LIVE_OR_PAST = Set.of("PUBLISHED", "SUPERSEDED");

    /**
     * Product script: DRAFT/VALIDATED -compile(ok)-> VALIDATED, -compile(failed)-> DRAFT; VALIDATED -submit->
     * PENDING_APPROVAL -approve-> ACTIVE; DRAFT/VALIDATED/PENDING_APPROVAL/ACTIVE -deprecate-> DEPRECATED.
     */
    public static final StateMachine SCRIPT = StateMachine.of("product script")
            .on("compile", Set.of("DRAFT", "VALIDATED", "REJECTED"), "VALIDATED")
            .on("compile_failed", Set.of("DRAFT", "VALIDATED", "REJECTED"), "DRAFT")
            .on("edit", Set.of("DRAFT", "VALIDATED", "REJECTED"), "DRAFT")
            .on("submit", "VALIDATED", "PENDING_APPROVAL")
            .on("approve", "PENDING_APPROVAL", "ACTIVE")
            .on("deprecate", Set.of("DRAFT", "VALIDATED", "PENDING_APPROVAL", "ACTIVE"), "DEPRECATED")
            .build();

    /** Simulation run: QUEUED -start-> RUNNING -> SUCCEEDED / FAILED; QUEUED/RUNNING -cancel-> CANCELLED. */
    public static final StateMachine SIMULATION = StateMachine.of("product simulation")
            .on("start", "QUEUED", "RUNNING")
            .on("succeed", "RUNNING", "SUCCEEDED")
            .on("fail", "RUNNING", "FAILED")
            .on("cancel", Set.of("QUEUED", "RUNNING"), "CANCELLED")
            .build();

    /** Promotion: SCHEDULED -start-> ACTIVE -expire-> EXPIRED; SCHEDULED/ACTIVE -cancel-> CANCELLED. */
    public static final StateMachine PROMOTION = StateMachine.of("promotional rate")
            .on("start", "SCHEDULED", "ACTIVE")
            .on("expire", Set.of("SCHEDULED", "ACTIVE"), "EXPIRED")
            .on("cancel", Set.of("SCHEDULED", "ACTIVE"), "CANCELLED")
            .build();

    /** Four-eyes rule: the checker must be a different principal from the maker. */
    public static void requireFourEyes(String maker, String checker, String what) {
        if (maker == null) {
            throw ApiException.conflict("maker_unknown", what + " has no recorded maker; resubmit it");
        }
        if (Objects.equals(maker, checker)) {
            throw ApiException.unprocessable("four_eyes_violation",
                    what + " was submitted by " + maker + "; a different user must approve it");
        }
    }
}
