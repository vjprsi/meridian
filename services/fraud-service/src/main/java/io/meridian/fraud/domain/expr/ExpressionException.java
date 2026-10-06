package io.meridian.fraud.domain.expr;

/** A syntax or validation error in a rule expression, with the character position where it was detected. */
public class ExpressionException extends RuntimeException {
    private final int position;

    public ExpressionException(int position, String message) {
        super(message + " (at " + position + ")");
        this.position = position;
    }

    public int position() {
        return position;
    }
}
