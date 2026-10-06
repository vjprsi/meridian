package io.meridian.businessday.domain;

/**
 * Lifecycle of a business date: OPEN -> CUT_OFF -> EOD_RUNNING -> (EOD_FAILED -> EOD_RUNNING)* -> CLOSED.
 * EOD_RUNNING also covers "EOD finished, awaiting the date roll"; the latest EOD run status tells them apart.
 */
public enum BusinessDayState {
    OPEN, CUT_OFF, EOD_RUNNING, EOD_FAILED, CLOSED;

    public static BusinessDayState of(String s) {
        return valueOf(s);
    }

    public void requireCutOff() {
        if (this != OPEN) {
            throw DomainException.invalidTransition("Business day", name(), "apply cut-off");
        }
    }

    public void requireStartEod() {
        if (this != OPEN && this != CUT_OFF) {
            throw DomainException.invalidTransition("Business day", name(), "start EOD");
        }
    }

    /** Restart / retry / skip re-enter EOD processing. */
    public void requireResumeEod() {
        if (this != EOD_RUNNING && this != EOD_FAILED) {
            throw DomainException.invalidTransition("Business day", name(), "resume EOD");
        }
    }

    public void requireRoll() {
        if (this != EOD_RUNNING && this != EOD_FAILED) {
            throw DomainException.invalidTransition("Business day", name(), "roll (EOD has not been run)");
        }
    }

    public void requireReopen() {
        if (this != CLOSED) {
            throw DomainException.invalidTransition("Business day", name(), "be reopened");
        }
    }
}
