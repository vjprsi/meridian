package io.meridian.fraud.domain.ml;

/** A model artefact that cannot be loaded; {@code code} is stable and snake_case. */
public class ArtifactException extends RuntimeException {
    private final String code;

    public ArtifactException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
