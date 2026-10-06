package io.meridian.referencedata.api;

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
 * Missing required query parameters are a client error (400 malformed_request), in the same problem+json shape as
 * the shared ProblemHandler, which does not map MissingServletRequestParameterException yet.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MissingParameterAdvice {
    private static final String TYPE_BASE = "https://docs.meridian.example/errors/";

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> missingParameter(MissingServletRequestParameterException e, HttpServletRequest req) {
        String code = "malformed_request";
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Required query parameter '" + e.getParameterName() + "' is missing");
        p.setType(URI.create(TYPE_BASE + code));
        p.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        p.setInstance(URI.create(req.getRequestURI()));
        p.setProperty("code", code);
        p.setProperty("request_id", req.getAttribute(RequestIdFilter.ATTRIBUTE));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(p);
    }
}
