package io.meridian.aiplatform.domain;

import io.meridian.common.error.ApiException;
import java.util.Map;
import java.util.Set;

/** State machines of model versions, deployments and copilots; invalid moves are 409 invalid_transition. */
public final class Lifecycles {
    private Lifecycles() {}

    public static final Set<String> ENVIRONMENTS = Set.of("SANDBOX", "STAGING", "PRODUCTION");
    /** Versions whose content can still be edited. */
    public static final Set<String> VERSION_EDITABLE = Set.of("TRAINING", "VALIDATING", "REJECTED");

    private static final Map<String, Set<String>> VERSION = Map.of(
            "TRAINING", Set.of("PENDING_APPROVAL"),
            "VALIDATING", Set.of("PENDING_APPROVAL"),
            "REJECTED", Set.of("PENDING_APPROVAL"),
            "PENDING_APPROVAL", Set.of("APPROVED", "REJECTED"),
            "APPROVED", Set.of("DEPLOYED", "RETIRED"),
            "DEPLOYED", Set.of("DEPLOYED", "RETIRED"));

    private static final Map<String, Set<String>> DEPLOYMENT = Map.of(
            "DEPLOYING", Set.of("ACTIVE", "ROLLED_BACK", "RETIRED"),
            "ACTIVE", Set.of("ACTIVE", "ROLLED_BACK", "RETIRED"),
            "ROLLED_BACK", Set.of("RETIRED"),
            "RETIRED", Set.of("ACTIVE"));

    private static final Map<String, Set<String>> COPILOT = Map.of(
            "DRAFT", Set.of("ACTIVE"),
            "ACTIVE", Set.of("DISABLED"),
            "DISABLED", Set.of("ACTIVE"));

    public static void version(String from, String to) {
        check("model version", VERSION, from, to);
    }

    public static void deployment(String from, String to) {
        check("deployment", DEPLOYMENT, from, to);
    }

    public static void copilot(String from, String to) {
        check("copilot", COPILOT, from, to);
    }

    private static void check(String what, Map<String, Set<String>> m, String from, String to) {
        if (!m.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.conflict("invalid_transition", "A " + what + " in status " + from + " cannot move to " + to);
        }
    }
}
