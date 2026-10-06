package io.meridian.notification.api;

import io.meridian.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A missing required query parameter (e.g. {@code tenant_id} of a provider callback) is a client error: 400
 * {@code malformed_request} as RFC 9457 problem details, in the shape of the shared ProblemHandler.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestProblems {
    private static final String TYPE_BASE = "https://docs.meridian.example/errors/";

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> missingParameter(MissingServletRequestParameterException e, HttpServletRequest req) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        p.setType(URI.create(TYPE_BASE + "malformed_request"));
        p.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        p.setInstance(URI.create(req.getRequestURI()));
        p.setProperty("code", "malformed_request");
        p.setProperty("request_id", req.getAttribute(RequestIdFilter.ATTRIBUTE));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(p);
    }
}
