package io.meridian.pricingrules.domain;

import io.meridian.common.error.ApiException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Maker-checker lifecycle of a decision-table version (DecisionTableStatus):
 * DRAFT -submit-> PENDING_APPROVAL -approve-> PUBLISHED -superseded-> RETIRED; PENDING_APPROVAL -reject-> DRAFT.
 * Only DRAFT versions are editable; PUBLISHED and RETIRED versions are immutable.
 */
public final class VersionLifecycle {
    public static final String DRAFT = "DRAFT";
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String RETIRED = "RETIRED";

    public enum Action { SUBMIT, APPROVE, REJECT, RETIRE }

    private static final Map<Action, Set<String>> FROM = Map.of(
            Action.SUBMIT, Set.of(DRAFT),
            Action.APPROVE, Set.of(PENDING_APPROVAL),
            Action.REJECT, Set.of(PENDING_APPROVAL),
            Action.RETIRE, Set.of(PUBLISHED));
    private static final Map<Action, String> TO = Map.of(
            Action.SUBMIT, PENDING_APPROVAL,
            Action.APPROVE, PUBLISHED,
            Action.REJECT, DRAFT,
            Action.RETIRE, RETIRED);

    private VersionLifecycle() {}

    /** Returns the target status or throws 409 invalid_transition. */
    public static String transition(String current, Action action) {
        if (!FROM.get(action).contains(current)) {
            throw ApiException.conflict("invalid_transition",
                    "Cannot " + action.name().toLowerCase(java.util.Locale.ROOT) + " a version in status " + current);
        }
        return TO.get(action);
    }

    public static void requireEditable(String status) {
        if (!DRAFT.equals(status)) {
            throw ApiException.conflict("version_not_editable",
                    "Only DRAFT versions can be changed; this version is " + status);
        }
    }

    /** Four-eyes: the checker must differ from the maker. */
    public static void requireDifferentChecker(String maker, String checker) {
        if (maker != null && Objects.equals(maker, checker)) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The version was submitted by " + maker + "; a different user must approve it");
        }
    }
}
