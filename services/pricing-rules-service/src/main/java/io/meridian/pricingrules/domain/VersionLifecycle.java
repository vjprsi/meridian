package io.meridian.pricingrules.domain;

import io.meridian.common.error.ApiException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Maker-checker lifecycle of a decision-table version (DecisionTableVersionStatus):
 * DRAFT/REJECTED -submit-> PENDING_APPROVAL -approve-> APPROVED -publish (effective_from reached)-> PUBLISHED
 * -superseded-> RETIRED; PENDING_APPROVAL -reject-> REJECTED (reworkable). APPROVED versions that are overtaken
 * by a later publication are retired too. Only DRAFT and REJECTED versions are editable.
 */
public final class VersionLifecycle {
    public static final String DRAFT = "DRAFT";
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String APPROVED = "APPROVED";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String REJECTED = "REJECTED";
    public static final String RETIRED = "RETIRED";

    public enum Action { SUBMIT, APPROVE, PUBLISH, REJECT, RETIRE }

    private static final Map<Action, Set<String>> FROM = Map.of(
            Action.SUBMIT, Set.of(DRAFT, REJECTED),
            Action.APPROVE, Set.of(PENDING_APPROVAL),
            Action.PUBLISH, Set.of(APPROVED),
            Action.REJECT, Set.of(PENDING_APPROVAL),
            Action.RETIRE, Set.of(PUBLISHED, APPROVED));
    private static final Map<Action, String> TO = Map.of(
            Action.SUBMIT, PENDING_APPROVAL,
            Action.APPROVE, APPROVED,
            Action.PUBLISH, PUBLISHED,
            Action.REJECT, REJECTED,
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
        if (!DRAFT.equals(status) && !REJECTED.equals(status)) {
            throw ApiException.conflict("version_not_editable",
                    "Only DRAFT or REJECTED versions can be changed; this version is " + status);
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
