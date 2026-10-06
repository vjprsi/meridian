package io.meridian.common.error;

import java.util.List;

/**
 * A business or request error that maps to an RFC 9457 problem response with a stable machine-readable code.
 */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final transient List<FieldError> errors;

    public ApiException(int status, String code, String detail) {
        this(status, code, detail, List.of());
    }

    public ApiException(int status, String code, String detail, List<FieldError> errors) {
        super(detail);
        this.status = status;
        this.code = code;
        this.errors = List.copyOf(errors);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldError> errors() {
        return errors;
    }

    public record FieldError(String field, String code, String message) {}

    public static ApiException notFound(String what, Object id) {
        return new ApiException(404, "not_found", what + " " + id + " was not found");
    }

    public static ApiException conflict(String code, String detail) {
        return new ApiException(409, code, detail);
    }

    public static ApiException unprocessable(String code, String detail) {
        return new ApiException(422, code, detail);
    }

    public static ApiException badRequest(String code, String detail) {
        return new ApiException(400, code, detail);
    }

    public static ApiException notImplemented(String operation) {
        return new ApiException(501, "not_implemented", operation + " is not implemented yet");
    }
}
