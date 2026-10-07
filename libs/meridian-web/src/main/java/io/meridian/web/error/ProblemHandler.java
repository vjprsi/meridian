package io.meridian.web.error;

import io.meridian.common.error.ApiException;
import io.meridian.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every error to an RFC 9457 {@code application/problem+json} body with a stable {@code code}. */
@RestControllerAdvice
public class ProblemHandler {
    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);
    private static final String TYPE_BASE = "https://docs.meridian.example/errors/";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e, HttpServletRequest req) {
        List<Map<String, String>> errors = e.errors().stream()
                .map(f -> Map.of("field", f.field(), "code", f.code(), "message", f.message()))
                .toList();
        return problem(HttpStatus.valueOf(e.status()), e.code(), e.getMessage(), req, errors);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", snake(f.getField()), "code", String.valueOf(f.getCode()),
                        "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed", req, errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> invalidParams(HandlerMethodValidationException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, "validation_failed", e.getMessage(), req, List.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class})
    ResponseEntity<ProblemDetail> badRequest(Exception e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, "malformed_request", e.getMessage(), req, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> methodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", e.getMessage(), req, List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest req) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", e.getMessage(), req, List.of());
    }

    /** The client's Accept header excludes every representation of the resource (e.g. a text/plain receipt). */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ProblemDetail> notAcceptable(HttpMediaTypeNotAcceptableException e, HttpServletRequest req) {
        return problem(HttpStatus.NOT_ACCEPTABLE, "not_acceptable", "None of the accepted media types can be produced; "
                + "supported: " + e.getSupportedMediaTypes(), req, List.of());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> missingParam(MissingServletRequestParameterException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, "missing_parameter",
                "Required query parameter '" + e.getParameterName() + "' is missing", req,
                List.of(Map.of("field", e.getParameterName(), "code", "required", "message", "is required")));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ProblemDetail> duplicate(DuplicateKeyException e, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, "duplicate", "A resource with the same unique key already exists",
                req, List.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> stale(OptimisticLockingFailureException e, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, "version_conflict",
                "The resource was changed by someone else; reload and retry with the new If-Match", req, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException e, HttpServletRequest req) {
        String msg = e.getMostSpecificCause().getMessage();
        if (msg != null && msg.contains("append-only")) {
            return problem(HttpStatus.CONFLICT, "immutable_record", "Record is append-only", req, List.of());
        }
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "constraint_violation", msg, req, List.of());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> dataAccess(DataAccessException e, HttpServletRequest req) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        if (msg.contains("append-only")) {
            return problem(HttpStatus.CONFLICT, "immutable_record", "Record is append-only; post a correction instead",
                    req, List.of());
        }
        if (msg.contains("violates check constraint")) {
            return problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_value", msg, req, List.of());
        }
        if (msg.contains("invalid input syntax") || msg.contains("out of range")) {
            return problem(HttpStatus.BAD_REQUEST, "invalid_value", msg, req, List.of());
        }
        log.error("Data access error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred; quote the request_id to support", req, List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException e, HttpServletRequest req) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "Not allowed", req, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> noRoute(NoResourceFoundException e, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, "not_found", "No such endpoint", req, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred; quote the request_id to support", req, List.of());
    }

    /** API fields are snake_case; bean-validation reports Java property names. */
    static String snake(String javaName) {
        return javaName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String code, String detail,
                                                 HttpServletRequest req, List<Map<String, String>> errors) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create(TYPE_BASE + code));
        p.setTitle(HttpStatus.valueOf(status.value()).getReasonPhrase());
        p.setInstance(URI.create(req.getRequestURI()));
        p.setProperty("code", code);
        p.setProperty("request_id", req.getAttribute(RequestIdFilter.ATTRIBUTE));
        if (!errors.isEmpty()) {
            p.setProperty("errors", errors);
        }
        return ResponseEntity.status(status).body(p);
    }
}
