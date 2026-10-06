package io.meridian.businessday.domain;

import java.util.EnumSet;
import java.util.Set;

/** End-of-period cycles. A broader cycle also runs the jobs of the narrower ones (EOY includes EOQ, EOM, EOD). */
public enum CycleType {
    EOD, EOM, EOQ, EOY, INTRADAY;

    public Set<CycleType> includedCycles() {
        return switch (this) {
            case EOD -> EnumSet.of(EOD);
            case EOM -> EnumSet.of(EOD, EOM);
            case EOQ -> EnumSet.of(EOD, EOM, EOQ);
            case EOY -> EnumSet.of(EOD, EOM, EOQ, EOY);
            case INTRADAY -> EnumSet.of(INTRADAY);
        };
    }

    public static CycleType parse(String value) {
        try {
            return CycleType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DomainException(422, "invalid_cycle_type", "Unknown cycle type " + value);
        }
    }
}
