package io.meridian.liquidity.domain;

/** A business rule violation detected by a pure domain calculation (mapped to 422 with {@code code}). */
public class DomainException extends RuntimeException {
    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
