package io.meridian.audit.api;

import org.springframework.http.ResponseEntity;

/** Helpers for operations the contract declares without a response schema. */
final class Responses {
    private Responses() {}

    /**
     * The generated interface returns {@code ResponseEntity<Void>} for actions whose spec says "none" although
     * they return a JSON document; generics are erased, so the body is serialised normally.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResponseEntity<Void> body(ResponseEntity<?> r) {
        return (ResponseEntity) r;
    }
}
