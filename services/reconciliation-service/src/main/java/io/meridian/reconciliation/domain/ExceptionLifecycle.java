package io.meridian.reconciliation.domain;

import io.meridian.common.error.ApiException;
import java.util.Locale;

/**
 * Reconciliation break lifecycle.
 * <pre>
 *   OPEN | INVESTIGATING --assign----------------> INVESTIGATING
 *   OPEN | INVESTIGATING --resolve---------------> RESOLVED (or WRITTEN_OFF when written off)
 *   OPEN | INVESTIGATING --accept-suggestion-----> RESOLVED
 *   OPEN | INVESTIGATING --auto-match in a run---> RESOLVED
 * </pre>
 * RESOLVED, WRITTEN_OFF and IGNORED are terminal.
 */
public final class ExceptionLifecycle {
    public enum Status { OPEN, INVESTIGATING, RESOLVED, WRITTEN_OFF, IGNORED }

    public enum Action { ASSIGN, RESOLVE, WRITE_OFF, ACCEPT_SUGGESTION, AUTO_MATCH }

    private ExceptionLifecycle() {}

    public static boolean isOpen(Status s) {
        return s == Status.OPEN || s == Status.INVESTIGATING;
    }

    public static Status next(Status from, Action action) {
        if (!isOpen(from)) {
            throw ApiException.conflict("invalid_transition", "Cannot " + action.name().toLowerCase(Locale.ROOT)
                    .replace('_', '-') + " a reconciliation exception in status " + from);
        }
        return switch (action) {
            case ASSIGN -> Status.INVESTIGATING;
            case RESOLVE, ACCEPT_SUGGESTION, AUTO_MATCH -> Status.RESOLVED;
            case WRITE_OFF -> Status.WRITTEN_OFF;
        };
    }
}
