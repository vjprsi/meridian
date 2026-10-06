package io.meridian.identity.domain;

import java.util.Map;
import java.util.Set;

/**
 * Staff user state machine.
 * <pre>
 * INVITED --(first MFA factor verified)--> ACTIVE
 * INVITED --invite--> INVITED (resend)
 * ACTIVE  --lock--> LOCKED --unlock--> ACTIVE
 * any but DEACTIVATED --deactivate--> DEACTIVATED (terminal)
 * </pre>
 */
public final class UserLifecycle {
    public enum Action { INVITE, ACTIVATE, LOCK, UNLOCK, DEACTIVATE }

    private static final Map<Action, Set<String>> FROM = Map.of(
            Action.INVITE, Set.of("INVITED"),
            Action.ACTIVATE, Set.of("INVITED"),
            Action.LOCK, Set.of("ACTIVE"),
            Action.UNLOCK, Set.of("LOCKED"),
            Action.DEACTIVATE, Set.of("INVITED", "ACTIVE", "LOCKED", "SUSPENDED"));

    private static final Map<Action, String> TO = Map.of(
            Action.INVITE, "INVITED",
            Action.ACTIVATE, "ACTIVE",
            Action.LOCK, "LOCKED",
            Action.UNLOCK, "ACTIVE",
            Action.DEACTIVATE, "DEACTIVATED");

    private UserLifecycle() {}

    public static boolean allowed(String current, Action action) {
        return FROM.get(action).contains(current);
    }

    /** Target status, or throws {@link IllegalStateException} when the transition is not allowed. */
    public static String next(String current, Action action) {
        if (!allowed(current, action)) {
            throw new IllegalStateException("cannot " + action.name().toLowerCase() + " a user in status " + current);
        }
        return TO.get(action);
    }
}
