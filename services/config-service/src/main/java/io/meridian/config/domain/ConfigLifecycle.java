package io.meridian.config.domain;

import io.meridian.common.error.ApiException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of a configuration version (ConfigEntryStatus).
 * <pre>
 * DRAFT --submit--> PENDING_APPROVAL --activate--> ACTIVE --(newer version activated)--> SUPERSEDED
 *                                                  ACTIVE --(rollback to an older version)--> ROLLED_BACK
 * </pre>
 * Only SUPERSEDED or ROLLED_BACK versions (previously approved values) can be rolled back to.
 */
public final class ConfigLifecycle {
    public enum Status { DRAFT, PENDING_APPROVAL, ACTIVE, SUPERSEDED, ROLLED_BACK }

    private static final Map<Status, Set<Status>> ALLOWED = Map.of(
            Status.DRAFT, EnumSet.of(Status.PENDING_APPROVAL),
            Status.PENDING_APPROVAL, EnumSet.of(Status.ACTIVE),
            Status.ACTIVE, EnumSet.of(Status.SUPERSEDED, Status.ROLLED_BACK),
            Status.SUPERSEDED, EnumSet.noneOf(Status.class),
            Status.ROLLED_BACK, EnumSet.noneOf(Status.class));

    private ConfigLifecycle() {}

    public static Status parse(String s) {
        return Status.valueOf(s);
    }

    public static boolean canTransition(Status from, Status to) {
        return ALLOWED.get(from).contains(to);
    }

    /** Throws 409 invalid_transition unless {@code from -> to} is allowed. */
    public static void require(String from, Status to) {
        Status f = parse(from);
        if (!canTransition(f, to)) {
            throw ApiException.conflict("invalid_transition",
                    "Config entry in status " + f + " cannot move to " + to);
        }
    }

    /** Throws 409 invalid_transition unless the version is a previously approved, no longer active value. */
    public static void requireRollbackTarget(String status) {
        Status s = parse(status);
        if (s != Status.SUPERSEDED && s != Status.ROLLED_BACK) {
            throw ApiException.conflict("invalid_transition",
                    "Only SUPERSEDED or ROLLED_BACK versions can be rolled back to; this version is " + s);
        }
    }
}
