package io.meridian.crm.domain;

import io.meridian.common.error.ApiException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Explicit action -> (from status -> to status) table. An action not allowed from the current status fails with
 * 409 {@code invalid_transition}. A target of {@link #KEEP} leaves the status unchanged (e.g. re-assignment).
 */
public final class StateMachine {
    public static final String KEEP = "*";

    private final String entity;
    private final Map<String, Map<String, String>> table = new HashMap<>();

    public StateMachine(String entity) {
        this.entity = entity;
    }

    public StateMachine on(String action, Set<String> from, String to) {
        Map<String, String> m = table.computeIfAbsent(action, k -> new HashMap<>());
        from.forEach(f -> m.put(f, to));
        return this;
    }

    public StateMachine on(String action, String from, String to) {
        return on(action, Set.of(from), to);
    }

    public boolean allows(String action, String from) {
        return table.getOrDefault(action, Map.of()).containsKey(from);
    }

    /** The status after the action; throws 409 invalid_transition when not allowed. */
    public String apply(String action, String from) {
        String to = table.getOrDefault(action, Map.of()).get(from);
        if (to == null) {
            throw ApiException.conflict("invalid_transition",
                    "Cannot " + action + " a " + entity + " in status " + from);
        }
        return KEEP.equals(to) ? from : to;
    }
}
