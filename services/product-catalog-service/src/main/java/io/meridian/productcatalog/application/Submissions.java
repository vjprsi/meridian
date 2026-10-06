package io.meridian.productcatalog.application;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Helpers for matching a workflow-service decision to the submission it was opened for (G11). */
final class Submissions {
    private Submissions() {}

    /**
     * True when both timestamps denote the same instant, or when the decision carries none (older requests).
     * Compared as instants so differences in offset formatting do not matter.
     */
    static boolean sameInstant(String expected, String actual) {
        if (expected == null) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        try {
            return OffsetDateTime.parse(expected).toInstant().equals(OffsetDateTime.parse(actual).toInstant());
        } catch (DateTimeParseException e) {
            return expected.equals(actual);
        }
    }
}
