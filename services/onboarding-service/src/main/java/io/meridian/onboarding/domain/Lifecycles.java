package io.meridian.onboarding.domain;

import io.meridian.common.error.ApiException;
import java.util.Map;
import java.util.Set;

/** Allowed source states per action for journeys, applications and offline batches. */
public final class Lifecycles {
    private Lifecycles() {}

    public static final Set<String> APP_OPEN = Set.of("STARTED", "IN_PROGRESS");
    public static final Set<String> APP_IN_REVIEW = Set.of("REFERRED", "UNDER_REVIEW");
    public static final Set<String> STEP_DONE = Set.of("COMPLETED", "FAILED", "WAITING_REVIEW", "SKIPPED");

    private static final Map<String, Set<String>> JOURNEY = Map.of(
            "publish", Set.of("DRAFT"),
            "retire", Set.of("DRAFT", "PUBLISHED"),
            "update", Set.of("DRAFT"));

    private static final Map<String, Set<String>> APPLICATION = Map.of(
            "update", APP_OPEN,
            "submit_step", APP_OPEN,
            "submit", APP_OPEN,
            "approve", APP_IN_REVIEW,
            "reject", Set.of("STARTED", "IN_PROGRESS", "SUBMITTED", "REFERRED", "UNDER_REVIEW"),
            "request_info", APP_IN_REVIEW,
            "abandon", APP_OPEN,
            "resume", Set.of("STARTED", "IN_PROGRESS", "ABANDONED"));

    private static final Map<String, Set<String>> BATCH = Map.of(
            "reprocess", Set.of("RECEIVED", "CONFLICT", "REJECTED"));

    public static void journey(String status, String action) {
        check("Journey", JOURNEY, status, action);
    }

    public static void application(String status, String action) {
        check("Application", APPLICATION, status, action);
    }

    public static void batch(String status, String action) {
        check("Offline batch", BATCH, status, action);
    }

    private static void check(String what, Map<String, Set<String>> rules, String status, String action) {
        if (!rules.get(action).contains(status)) {
            throw ApiException.conflict("invalid_transition",
                    what + " in status " + status + " does not allow " + action.replace('_', '-'));
        }
    }
}
