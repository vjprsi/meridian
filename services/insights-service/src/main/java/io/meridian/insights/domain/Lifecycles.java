package io.meridian.insights.domain;

import io.meridian.common.error.ApiException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Allowed status transitions per action; anything else is 409 {@code invalid_transition}. */
public final class Lifecycles {
    private Lifecycles() {}

    public static final String KEEP = "*";

    public static final Lifecycle INSIGHT = new Lifecycle("insight")
            .on("seen", Set.of("PENDING", "DELIVERED", "SEEN"), "SEEN")
            .on("acted", Set.of("PENDING", "DELIVERED", "SEEN", "ACTED"), "ACTED")
            .on("dismiss", Set.of("PENDING", "DELIVERED", "SEEN", "DISMISSED"), "DISMISSED")
            .on("feedback", Set.of("PENDING", "DELIVERED", "SEEN", "ACTED", "DISMISSED"), KEEP)
            .on("deliver", Set.of("PENDING"), "DELIVERED")
            .on("expire", Set.of("PENDING", "DELIVERED", "SEEN"), "EXPIRED");

    public static final Lifecycle RECURRING = new Lifecycle("recurring payment")
            .on("confirm", Set.of("DETECTED", "INACTIVE", "CONFIRMED"), "CONFIRMED")
            .on("dismiss", Set.of("DETECTED", "CONFIRMED", "INACTIVE", "DISMISSED"), "DISMISSED")
            .on("block", Set.of("DETECTED", "CONFIRMED", "INACTIVE"), "CANCELLED_BY_CUSTOMER")
            .on("retire", Set.of("DETECTED", "CONFIRMED"), "INACTIVE");

    public static final Lifecycle BUDGET = new Lifecycle("budget")
            .on("pause", Set.of("ACTIVE"), "PAUSED")
            .on("resume", Set.of("PAUSED"), "ACTIVE")
            .on("archive", Set.of("ACTIVE", "PAUSED"), "ARCHIVED");

    public static final class Lifecycle {
        private final String entity;
        private final Map<String, Map<String, String>> table = new HashMap<>();

        Lifecycle(String entity) {
            this.entity = entity;
        }

        Lifecycle on(String action, Set<String> from, String to) {
            Map<String, String> m = table.computeIfAbsent(action, k -> new HashMap<>());
            from.forEach(f -> m.put(f, to));
            return this;
        }

        public boolean allows(String action, String from) {
            return table.getOrDefault(action, Map.of()).containsKey(from);
        }

        public String apply(String action, String from) {
            String to = table.getOrDefault(action, Map.of()).get(from);
            if (to == null) {
                throw ApiException.conflict("invalid_transition", "Cannot " + action + " a " + entity + " in status " + from);
            }
            return KEEP.equals(to) ? from : to;
        }
    }
}
