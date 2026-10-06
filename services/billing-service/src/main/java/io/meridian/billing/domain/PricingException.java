package io.meridian.billing.domain;

/** Invalid price definition or pricing input (mapped to 422 by the application layer). */
public class PricingException extends RuntimeException {
    public PricingException(String message) {
        super(message);
    }
}
