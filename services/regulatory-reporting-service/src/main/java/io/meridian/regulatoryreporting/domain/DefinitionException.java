package io.meridian.regulatoryreporting.domain;

import java.util.List;

/** A report definition (data sources, field mappings or validation rules) is invalid; carries every problem found. */
public final class DefinitionException extends RuntimeException {
    private final List<Problem> problems;

    public DefinitionException(List<Problem> problems) {
        super(problems.isEmpty() ? "invalid definition" : problems.getFirst().field() + ": " + problems.getFirst().message());
        this.problems = List.copyOf(problems);
    }

    public List<Problem> problems() {
        return problems;
    }

    /** One problem: the JSON path of the offending element and a message. */
    public record Problem(String field, String message) {}
}
