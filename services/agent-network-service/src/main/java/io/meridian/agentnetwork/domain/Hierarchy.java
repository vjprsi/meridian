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

    // ------------------------------------------------------------------ WE-HIER-1: configurable Domain -> Category -> Grade

    /** Code of the factory distribution domain seeded by the WALLET starter pack. */
    public static final String FACTORY_DOMAIN_CODE = "DISTRIBUTION";

    /**
     * Compat path: the factory {@code domain_category} code a legacy {@link io.meridian.agentnetwork.domain}
     * {@code AgentLevel} maps onto (the WALLET pack seeds categories with these codes under the factory domain), so
     * existing {@code agent_level} callers and journey D keep validating against the configurable hierarchy.
     */
    public static String legacyCategoryCode(String agentLevel) {
        return switch (agentLevel) {
            case "SUPER_AGENT" -> "SUPER_AGENT";
            case "AGENT" -> "AGENT";
            case "SUB_AGENT" -> "SUB_AGENT";
            default -> throw ApiException.unprocessable("invalid_agent_level", "Unknown agent level " + agentLevel);
        };
    }

    /** Depth of a category in its chain: 1 for an owner (no parent), parent.level + 1 otherwise. */
    public static int deriveLevel(Integer parentLevel) {
        return parentLevel == null ? 1 : parentLevel + 1;
    }

    /**
     * Validates a category's placement by its parent chain (generalises the old fixed SUPER_AGENT/AGENT/SUB_AGENT
     * switch): an owner has no parent and sits at level 1; a non-owner needs an ACTIVE parent in the same domain and
     * derives its level from it. Returns the derived level. Cycle detection is done against the stored chain by the caller.
     *
     * @param domainId          the category's domain
     * @param parentId          requested parent category (null = owner)
     * @param parentDomainId    the parent's domain (null when there is no parent)
     * @param parentLevel       the parent's level (null when there is no parent)
     * @param parentStatus      the parent's lifecycle status (null when there is no parent)
     * @param ownerExists       whether the domain already has an owner category (other than this one)
     */
    public static int validateCategoryParent(UUID domainId, UUID parentId, UUID parentDomainId, Integer parentLevel,
                                              String parentStatus, boolean ownerExists) {
        if (parentId == null) {
            if (ownerExists) {
                throw ApiException.unprocessable("owner_exists",
                        "The domain already has an owner (level-1) category; a new category needs a parent");
            }
            return 1;
        }
        if (parentDomainId == null) {
            throw ApiException.unprocessable("parent_not_found", "The parent category does not exist");
        }
        if (!domainId.equals(parentDomainId)) {
            throw ApiException.unprocessable("different_domain", "A category and its parent must be in the same domain");
        }
        if ("RETIRED".equals(parentStatus)) {
            throw ApiException.unprocessable("parent_retired", "The parent category is retired");
        }
        return deriveLevel(parentLevel);
    }

    private static final Map<String, Set<String>> CONFIG = Map.of(
            "DRAFT", Set.of("PENDING_APPROVAL", "RETIRED"),
            "PENDING_APPROVAL", Set.of("ACTIVE", "DRAFT"),
            "ACTIVE", Set.of("RETIRED"),
            "RETIRED", Set.of());

    /** Maker-checker lifecycle transitions of a hierarchy config row (domain, category, grade). */
    public static void requireConfigTransition(String from, String to) {
        if (!CONFIG.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.conflict("invalid_transition", "Config cannot move from " + from + " to " + to);
        }
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
