package io.meridian.customer.domain;

import io.meridian.common.error.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** A named set of status transitions: action -> (allowed source statuses, target status). */
public final class StateMachine {
    private record Rule(Set<String> from, String to) {}

    private final String entity;
    private final Map<String, Rule> rules = new LinkedHashMap<>();

    public StateMachine(String entity) {
        this.entity = entity;
    }

    public StateMachine on(String action, String to, String... from) {
        rules.put(action, new Rule(Set.of(from), to));
        return this;
    }

    public boolean allows(String action, String current) {
        Rule r = rules.get(action);
        return r != null && r.from().contains(current);
    }

    /** Target status of {@code action} from {@code current}; 409 invalid_transition when not allowed. */
    public String apply(String action, String current) {
        Rule r = rules.get(action);
        if (r == null) {
            throw new IllegalArgumentException("unknown action " + action);
        }
        if (!r.from().contains(current)) {
            throw ApiException.conflict("invalid_transition", "Cannot " + action + " a " + entity + " in status "
                    + current + "; allowed from " + new TreeSet<>(r.from()));
        }
        return r.to();
    }
}
