package io.meridian.agentnetwork.domain;

import io.meridian.common.error.ApiException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Agent hierarchy and lifecycle rules. A SUPER_AGENT is a root; an AGENT reports to a SUPER_AGENT; a SUB_AGENT reports
 * to an AGENT. The top of every chain is the super-agent.
 */
public final class Hierarchy {
    private Hierarchy() {}

    /** Required parent level for a level (null = must be a root). */
    public static String parentLevelOf(String level) {
        return switch (level) {
            case "SUPER_AGENT" -> null;
            case "AGENT" -> "SUPER_AGENT";
            case "SUB_AGENT" -> "AGENT";
            default -> throw ApiException.unprocessable("invalid_agent_level", "Unknown agent level " + level);
        };
    }

    /**
     * Validates the parent of a new agent; returns the derived super_agent_id (null for a super-agent).
     *
     * @param parentId         requested parent (may be null)
     * @param parentLevel      level of the parent (null when there is no parent)
     * @param parentStatus     status of the parent
     * @param parentSuperAgent super_agent_id of the parent
     */
    public static UUID validateParent(String level, UUID parentId, String parentLevel, String parentStatus,
                                      UUID parentSuperAgent) {
        String required = parentLevelOf(level);
        if (required == null) {
            if (parentId != null) {
                throw ApiException.unprocessable("invalid_hierarchy", "A super-agent cannot have a parent agent");
            }
            return null;
        }
        if (parentId == null) {
            throw ApiException.unprocessable("invalid_hierarchy", "An " + level + " needs a parent " + required);
        }
        if (!required.equals(parentLevel)) {
            throw ApiException.unprocessable("invalid_hierarchy",
                    "The parent of a " + level + " must be a " + required + ", not a " + parentLevel);
        }
        if ("TERMINATED".equals(parentStatus)) {
            throw ApiException.unprocessable("parent_terminated", "The parent agent is terminated");
        }
        return "SUPER_AGENT".equals(parentLevel) ? parentId : parentSuperAgent;
    }

    /** Root of an agent's tree: its super-agent, or itself for a super-agent. */
    public static UUID root(UUID id, UUID superAgentId) {
        return superAgentId == null ? id : superAgentId;
    }

    private static final Map<String, Set<String>> AGENT = Map.of(
            "ONBOARDING", Set.of("ACTIVE", "TERMINATED"),
            "ACTIVE", Set.of("SUSPENDED", "TERMINATED"),
            "SUSPENDED", Set.of("ACTIVE", "TERMINATED"),
            "TERMINATED", Set.of());

    public static void requireAgentTransition(String from, String to) {
        if (!AGENT.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.conflict("invalid_transition", "Agent cannot move from " + from + " to " + to);
        }
    }

    private static final Map<String, Set<String>> DEVICE = Map.of(
            "ACTIVE", Set.of("INACTIVE", "LOST", "RETIRED"),
            "INACTIVE", Set.of("ACTIVE", "LOST", "RETIRED"),
            "LOST", Set.of("RETIRED"),
            "RETIRED", Set.of());

    public static void requireDeviceTransition(String from, String to) {
        if (!DEVICE.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.conflict("invalid_transition", "Device cannot move from " + from + " to " + to);
        }
    }

    private static final Map<String, Set<String>> REQUEST = Map.of(
            "REQUESTED", Set.of("APPROVED", "REJECTED", "CANCELLED", "COMPLETED", "FAILED"),
            "APPROVED", Set.of("COMPLETED", "CANCELLED", "FAILED"),
            "COMPLETED", Set.of(), "REJECTED", Set.of(), "CANCELLED", Set.of(), "FAILED", Set.of());

    /** Float requests and cash-management requests share FloatRequestStatus. */
    public static void requireRequestTransition(String from, String to) {
        if (!REQUEST.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.conflict("invalid_transition", "Request cannot move from " + from + " to " + to);
        }
    }
}
