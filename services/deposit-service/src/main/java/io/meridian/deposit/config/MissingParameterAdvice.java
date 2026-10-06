package io.meridian.deposit.config;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 400 {@code missing_parameter} for a required query parameter the contract declares (e.g. {@code business_date},
 * {@code deposit_account_id}). The shared ProblemHandler in meridian-web does not map this exception yet and would
 * answer 500; remove this advice once it does.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MissingParameterAdvice {
    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> missing(MissingServletRequestParameterException e, HttpServletRequest req) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Query parameter " + e.getParameterName() + " is required");
        p.setType(URI.create("https://docs.meridian.example/errors/missing_parameter"));
        p.setTitle("Bad Request");
        p.setInstance(URI.create(req.getRequestURI()));
        p.setProperty("code", "missing_parameter");
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }
}
