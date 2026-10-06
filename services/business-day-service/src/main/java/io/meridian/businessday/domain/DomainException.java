package io.meridian.businessday.domain;

import io.meridian.common.error.ApiException;

/**
 * A business-rule violation raised by the domain model. Extends the platform {@link ApiException} (a plain
 * library type from meridian-common, not Spring) so it maps to a problem response with a stable code.
 */
public class DomainException extends ApiException {
    public DomainException(int status, String code, String detail) {
        super(status, code, detail);
    }

    public static DomainException invalidTransition(String what, String from, String action) {
        return new DomainException(409, "invalid_transition", what + " in state " + from + " cannot " + action);
    }
}
