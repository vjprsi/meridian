package io.meridian.gl.domain;

import io.meridian.common.error.ApiException;
import java.util.Locale;

/**
 * State machine of an accounting period.
 * <pre>
 *   OPEN | REOPENED      --close (maker)-----------> SOFT_CLOSED
 *   SOFT_CLOSED          --approve-close (checker)-> CLOSED
 *   SOFT_CLOSED | CLOSED --reopen (reason)---------> REOPENED
 * </pre>
 */
public final class PeriodLifecycle {
    public enum Action { CLOSE, APPROVE_CLOSE, REOPEN }

    private PeriodLifecycle() {}

    public static PeriodStatus next(PeriodStatus from, Action action) {
        PeriodStatus to = switch (action) {
            case CLOSE -> from == PeriodStatus.OPEN || from == PeriodStatus.REOPENED ? PeriodStatus.SOFT_CLOSED : null;
            case APPROVE_CLOSE -> from == PeriodStatus.SOFT_CLOSED ? PeriodStatus.CLOSED : null;
            case REOPEN -> from == PeriodStatus.CLOSED || from == PeriodStatus.SOFT_CLOSED ? PeriodStatus.REOPENED : null;
        };
        if (to == null) {
            throw ApiException.conflict("invalid_transition",
                    "Cannot " + action.name().toLowerCase(Locale.ROOT).replace('_', '-')
                            + " an accounting period in status " + from);
        }
        return to;
    }
}
