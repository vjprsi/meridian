package io.meridian.regulatoryreporting.domain;

import io.meridian.common.error.ApiException;
import java.util.Map;
import java.util.Set;

/** State machines of runs, submissions, adjustments and definition versions. Invalid moves are 409 invalid_transition. */
public final class Lifecycles {
    private Lifecycles() {}

    public static final Set<String> RUN_FINISHED = Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELLED");
    public static final Set<String> RUN_PRODUCED = Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED");
    /** Submissions that block another submission of the same run. */
    public static final Set<String> SUBMISSION_OPEN = Set.of("PENDING_APPROVAL", "APPROVED", "SUBMITTED", "ACKNOWLEDGED", "ACCEPTED");
    /** Submissions that reached the regulator. */
    public static final Set<String> SUBMISSION_FILED = Set.of("SUBMITTED", "ACKNOWLEDGED", "ACCEPTED");

    private static final Map<String, Set<String>> RUN = Map.of(
            "QUEUED", Set.of("RUNNING", "CANCELLED"),
            "RUNNING", Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELLED"),
            "PARTIALLY_SUCCEEDED", Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED"),
            "SUCCEEDED", Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED"));

    private static final Map<String, Set<String>> SUBMISSION = Map.of(
            "PENDING_APPROVAL", Set.of("APPROVED"),
            "APPROVED", Set.of("SUBMITTED", "REJECTED"),
            "SUBMITTED", Set.of("ACKNOWLEDGED", "ACCEPTED", "REJECTED"),
            "ACKNOWLEDGED", Set.of("ACCEPTED", "REJECTED"));

    private static final Map<String, Set<String>> ADJUSTMENT = Map.of(
            "PENDING_APPROVAL", Set.of("APPROVED", "REJECTED"));

    private static final Map<String, Set<String>> VERSION = Map.of(
            "DRAFT", Set.of("ACTIVE"),
            "ACTIVE", Set.of("SUPERSEDED", "DEPRECATED"));

    public static void run(String from, String to) {
        check("report run", RUN, from, to);
    }

    public static void submission(String from, String to) {
        check("submission", SUBMISSION, from, to);
    }

    public static void adjustment(String from, String to) {
        check("adjustment", ADJUSTMENT, from, to);
    }

    public static void version(String from, String to) {
        check("definition version", VERSION, from, to);
    }

    private static void check(String what, Map<String, Set<String>> machine, String from, String to) {
        if (!machine.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.conflict("invalid_transition", "A " + what + " in status " + from + " cannot move to " + to);
        }
    }
}
