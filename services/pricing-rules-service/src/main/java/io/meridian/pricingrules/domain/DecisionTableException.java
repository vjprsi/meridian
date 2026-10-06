package io.meridian.pricingrules.domain;

import java.util.List;

/** A decision-table definition or evaluation error with a stable snake_case code and optional violations. */
public class DecisionTableException extends RuntimeException {
    private final String code;
    private final transient List<Violation> violations;

    public DecisionTableException(String code, String message) {
        this(code, message, List.of());
    }

    public DecisionTableException(String code, String message, List<Violation> violations) {
        super(message);
        this.code = code;
        this.violations = List.copyOf(violations);
    }

    public String code() {
        return code;
    }

    public List<Violation> violations() {
        return violations;
    }

    /** One problem in a definition: where (e.g. rules[2].inputs[0]), a code and a message. */
    public record Violation(String field, String code, String message) {}
}
