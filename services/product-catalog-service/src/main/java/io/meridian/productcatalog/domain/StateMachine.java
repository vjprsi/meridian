package io.meridian.productcatalog.domain;

import io.meridian.common.error.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A small explicit state machine: action -> (allowed source states -> target state). Invalid transitions are
 * rejected with 409 {@code invalid_transition}.
 */
public final class StateMachine {
    private final String aggregate;
    private final Map<String, Map<String, String>> transitions;

    private StateMachine(String aggregate, Map<String, Map<String, String>> transitions) {
        this.aggregate = aggregate;
        this.transitions = transitions;
    }

    public static Builder of(String aggregate) {
        return new Builder(aggregate);
    }

    /** Target state of {@code action} from {@code current}, or 409 invalid_transition. */
    public String apply(String current, String action) {
        Map<String, String> from = transitions.get(action);
        if (from == null) {
            throw new IllegalArgumentException("unknown action " + action + " for " + aggregate);
        }
        String to = from.get(current);
        if (to == null) {
            throw ApiException.conflict("invalid_transition",
                    "Cannot " + action + " a " + aggregate + " in status " + current + " (allowed from " + from.keySet() + ")");
        }
        return to;
    }

    public boolean allows(String current, String action) {
        Map<String, String> from = transitions.get(action);
        return from != null && from.containsKey(current);
    }

    public static final class Builder {
        private final String aggregate;
        private final Map<String, Map<String, String>> transitions = new LinkedHashMap<>();

        private Builder(String aggregate) {
            this.aggregate = aggregate;
        }

        public Builder on(String action, Set<String> from, String to) {
            Map<String, String> m = transitions.computeIfAbsent(action, a -> new LinkedHashMap<>());
            from.forEach(f -> m.put(f, to));
            return this;
        }

        public Builder on(String action, String from, String to) {
            return on(action, Set.of(from), to);
        }

        public StateMachine build() {
            Map<String, Map<String, String>> copy = new LinkedHashMap<>();
            transitions.forEach((k, v) -> copy.put(k, Map.copyOf(v)));
            return new StateMachine(aggregate, Map.copyOf(copy));
        }
    }
}
