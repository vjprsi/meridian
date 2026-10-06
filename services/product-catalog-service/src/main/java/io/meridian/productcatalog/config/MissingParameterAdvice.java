package io.meridian.productcatalog.config;

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
 * A required query parameter declared by the contract (e.g. {@code ?against=} on the version diff) that is missing
 * is a client error: 400 {@code missing_parameter} in the platform's problem format (the shared handler does not
 * map {@link MissingServletRequestParameterException} yet and would answer 500).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MissingParameterAdvice {
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> missing(MissingServletRequestParameterException e, HttpServletRequest req) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Query parameter " + e.getParameterName() + " is required");
        p.setType(URI.create("https://docs.meridian.example/errors/missing_parameter"));
        p.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        p.setInstance(URI.create(req.getRequestURI()));
        p.setProperty("code", "missing_parameter");
        p.setProperty("request_id", req.getAttribute(RequestIdFilter.ATTRIBUTE));
        return ResponseEntity.badRequest().body(p);
    }
}
