package io.meridian.workflow.domain;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Multi-level approval routing. A policy's {@code approval_levels} is
 * {@code {"levels":[{"level":1,"approver_roles":["CHECKER"],"min_amount":"0","required_approvals":1}, ...]}}.
 * A level applies to a request when it has no {@code min_amount} or the request amount reaches it; the applicable
 * levels, in order, form the request's route, which is snapshotted on the request so later policy edits do not
 * change an in-flight approval.
 */
public final class ApprovalRoute {

    public record Level(int level, Set<String> approverRoles, BigDecimal minAmount, int requiredApprovals) {
        public boolean allows(Collection<String> callerRoles) {
            return approverRoles.isEmpty() || callerRoles.stream().anyMatch(approverRoles::contains);
        }
    }

    private final List<Level> levels;

    private ApprovalRoute(List<Level> levels) {
        this.levels = List.copyOf(levels);
    }

    public List<Level> levels() {
        return levels;
    }

    public int totalRequiredApprovals() {
        return levels.stream().mapToInt(Level::requiredApprovals).sum();
    }

    /** Level by 1-based position in the route (the request's {@code current_level}). */
    public Level at(int position) {
        if (position < 1 || position > levels.size()) {
            throw ApiException.conflict("invalid_level", "Approval level " + position + " is not on the route");
        }
        return levels.get(position - 1);
    }

    public boolean isLast(int position) {
        return position >= levels.size();
    }

    /** Default route when no policy matches: one level, one approval by any other user. */
    public static ApprovalRoute single(int requiredApprovals) {
        return new ApprovalRoute(List.of(new Level(1, Set.of(), null, Math.max(1, requiredApprovals))));
    }

    /**
     * Validates a policy's level configuration.
     *
     * @param defaultRequired the policy's {@code required_approvals}, used where a level does not override it
     */
    public static List<Level> parsePolicyLevels(JsonNode approvalLevels, int defaultRequired) {
        if (defaultRequired < 1) {
            throw ApiException.unprocessable("invalid_approval_levels", "required_approvals must be at least 1");
        }
        JsonNode arr = approvalLevels == null ? null : approvalLevels.get("levels");
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            throw ApiException.unprocessable("invalid_approval_levels",
                    "approval_levels must be {\"levels\":[...]} with at least one level");
        }
        List<Level> out = new ArrayList<>();
        BigDecimal previousMin = null;
        int expected = 1;
        for (JsonNode n : arr) {
            if (!n.isObject()) {
                throw ApiException.unprocessable("invalid_approval_levels", "each level must be an object");
            }
            int level = n.has("level") ? n.get("level").asInt() : expected;
            if (level != expected) {
                throw ApiException.unprocessable("invalid_approval_levels",
                        "levels must be numbered 1..n in order; expected " + expected + " but got " + level);
            }
            Set<String> roles = new LinkedHashSet<>();
            JsonNode r = n.get("approver_roles");
            if (r != null && !r.isNull()) {
                if (!r.isArray()) {
                    throw ApiException.unprocessable("invalid_approval_levels", "approver_roles must be an array");
                }
                r.forEach(x -> {
                    if (x.asString().isBlank()) {
                        throw ApiException.unprocessable("invalid_approval_levels", "approver role must not be blank");
                    }
                    roles.add(x.asString());
                });
            }
            BigDecimal min = null;
            if (n.hasNonNull("min_amount")) {
                try {
                    min = new BigDecimal(n.get("min_amount").asString());
                } catch (NumberFormatException e) {
                    throw ApiException.unprocessable("invalid_approval_levels", "min_amount must be a decimal string");
                }
                if (min.signum() < 0) {
                    throw ApiException.unprocessable("invalid_approval_levels", "min_amount must not be negative");
                }
                if (previousMin != null && min.compareTo(previousMin) < 0) {
                    throw ApiException.unprocessable("invalid_approval_levels",
                            "min_amount thresholds must be non-decreasing across levels");
                }
                previousMin = min;
            }
            int required = n.hasNonNull("required_approvals") ? n.get("required_approvals").asInt() : defaultRequired;
            if (required < 1 || required > 10) {
                throw ApiException.unprocessable("invalid_approval_levels", "required_approvals must be 1..10");
            }
            out.add(new Level(level, roles, min, required));
            expected++;
        }
        return out;
    }

    /** Route for a request with the given amount (null = no amount: only levels without a positive threshold). */
    public static ApprovalRoute forAmount(List<Level> policyLevels, BigDecimal amount) {
        List<Level> applicable = new ArrayList<>();
        for (Level l : policyLevels) {
            boolean applies = l.minAmount() == null || l.minAmount().signum() == 0
                    || (amount != null && amount.compareTo(l.minAmount()) >= 0);
            if (applies) {
                applicable.add(l);
            }
        }
        if (applicable.isEmpty()) {
            applicable.add(policyLevels.getFirst());
        }
        return new ApprovalRoute(applicable);
    }

    public ArrayNode toJson() {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        for (Level l : levels) {
            ObjectNode o = arr.addObject();
            o.put("level", l.level());
            ArrayNode roles = o.putArray("approver_roles");
            l.approverRoles().forEach(roles::add);
            if (l.minAmount() != null) {
                o.put("min_amount", l.minAmount().toPlainString());
            }
            o.put("required_approvals", l.requiredApprovals());
        }
        return arr;
    }

    public static ApprovalRoute fromJson(JsonNode route) {
        if (route == null || !route.isArray() || route.isEmpty()) {
            return single(1);
        }
        List<Level> levels = new ArrayList<>();
        for (JsonNode n : route) {
            Set<String> roles = new LinkedHashSet<>();
            n.path("approver_roles").forEach(x -> roles.add(x.asString()));
            BigDecimal min = n.hasNonNull("min_amount") ? new BigDecimal(n.get("min_amount").asString()) : null;
            levels.add(new Level(n.path("level").asInt(1), roles, min, n.path("required_approvals").asInt(1)));
        }
        return new ApprovalRoute(levels);
    }
}
