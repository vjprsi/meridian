package io.meridian.rewards.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Collects field errors and throws one 422 with all of them. */
final class Problems {
    static final Pattern HHMM = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    static final Pattern MCC = Pattern.compile("\\d{4}");
    static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_\\-]{0,31}");

    private final List<FieldError> errors = new ArrayList<>();
    private final String code;

    Problems(String code) {
        this.code = code;
    }

    Problems add(String field, String errCode, String message) {
        errors.add(new FieldError(field, errCode, message));
        return this;
    }

    Problems check(boolean ok, String field, String errCode, String message) {
        if (!ok) {
            add(field, errCode, message);
        }
        return this;
    }

    Problems positive(BigDecimal v, String field) {
        return check(v == null || v.signum() > 0, field, "must_be_positive", field + " must be > 0");
    }

    Problems nonNegative(BigDecimal v, String field) {
        return check(v == null || v.signum() >= 0, field, "must_not_be_negative", field + " must be >= 0");
    }

    Problems hhmm(String v, String field) {
        return check(v == null || HHMM.matcher(v).matches(), field, "invalid_time", field + " must be HH:mm");
    }

    Problems zone(String v, String field) {
        if (v != null) {
            try {
                ZoneId.of(v);
            } catch (DateTimeException e) {
                add(field, "invalid_time_zone", v + " is not an IANA time zone");
            }
        }
        return this;
    }

    void throwIfAny() {
        if (!errors.isEmpty()) {
            throw new ApiException(422, code, errors.getFirst().message(), errors);
        }
    }

    static LocalTime time(String v) {
        return v == null ? null : LocalTime.parse(v);
    }
}
