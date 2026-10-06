package io.meridian.identity.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Access decision: RBAC first (the subject must hold a permission for the action), then ABAC policies.
 * Policies are evaluated by ascending priority; the first priority level with at least one applicable policy whose
 * condition holds decides, and DENY wins over ALLOW on the same level. If no policy applies the RBAC result stands.
 * A condition that fails to evaluate is treated fail-closed: it applies for DENY policies and not for ALLOW ones.
 */
public final class AccessEvaluator {

    public record Policy(String id, String name, String effect, int priority, String resourcePattern,
                         List<String> actions, String condition) {}

    public record Decision(String decision, boolean rbacGranted, String reason, String decidingPolicyId,
                           List<Map<String, Object>> trace) {
        public boolean allowed() {
            return "ALLOW".equals(decision);
        }
    }

    private AccessEvaluator() {}

    public static Decision decide(String action, boolean rbacGranted, List<Policy> policies, Map<String, Object> ctx) {
        List<Map<String, Object>> trace = new ArrayList<>();
        if (!rbacGranted) {
            return new Decision("DENY", false, "no_permission", null, trace);
        }
        List<Policy> sorted = new ArrayList<>(policies);
        sorted.sort(Comparator.comparingInt(Policy::priority));
        Integer decidingLevel = null;
        Policy allowHit = null;
        Policy denyHit = null;
        for (Policy p : sorted) {
            if (decidingLevel != null && p.priority() != decidingLevel) {
                break;
            }
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("policy_id", p.id());
            t.put("name", p.name());
            t.put("effect", p.effect());
            t.put("priority", p.priority());
            boolean applicable = matches(p.resourcePattern(), action) && actionCovered(p.actions(), action);
            t.put("applicable", applicable);
            if (applicable) {
                boolean holds;
                try {
                    holds = PolicyExpression.parse(p.condition()).evaluate(ctx);
                } catch (IllegalArgumentException e) {
                    holds = "DENY".equals(p.effect());
                    t.put("error", e.getMessage());
                }
                t.put("condition_result", holds);
                if (holds) {
                    decidingLevel = p.priority();
                    if ("DENY".equals(p.effect())) {
                        denyHit = denyHit == null ? p : denyHit;
                    } else {
                        allowHit = allowHit == null ? p : allowHit;
                    }
                }
            }
            trace.add(t);
        }
        if (denyHit != null) {
            return new Decision("DENY", true, "policy_denied", denyHit.id(), trace);
        }
        if (allowHit != null) {
            return new Decision("ALLOW", true, "policy_allowed", allowHit.id(), trace);
        }
        return new Decision("ALLOW", true, "rbac_granted", null, trace);
    }

    /** Glob match where {@code *} matches any sequence (including dots). */
    public static boolean matches(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        StringBuilder re = new StringBuilder();
        for (String part : pattern.split("\\*", -1)) {
            if (!re.isEmpty()) {
                re.append(".*");
            }
            re.append(Pattern.quote(part));
        }
        return Pattern.matches(re.toString(), value);
    }

    static boolean actionCovered(List<String> actions, String action) {
        if (actions == null || actions.isEmpty()) {
            return false;
        }
        String verb = action.contains(".") ? action.substring(action.lastIndexOf('.') + 1) : action;
        return actions.stream().anyMatch(a -> a.equals("*") || a.equals(verb) || matches(a, action));
    }
}
