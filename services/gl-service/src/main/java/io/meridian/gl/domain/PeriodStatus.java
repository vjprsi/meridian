package io.meridian.gl.domain;

/** Accounting period lifecycle states (spec enum PeriodStatus). */
public enum PeriodStatus {
    OPEN, SOFT_CLOSED, CLOSED, REOPENED;

    /** Only open (or reopened) periods accept GL postings; soft-closed and closed periods are frozen. */
    public boolean acceptsPostings() {
        return this == OPEN || this == REOPENED;
    }
}
