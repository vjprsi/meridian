package io.meridian.web;

import io.meridian.common.id.Ids;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Accepts or creates an {@code X-Request-Id}, echoes it on the response and puts it in the log MDC. */
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "meridian.requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = req.getHeader(HEADER);
        if (id == null || id.isBlank() || id.length() > 128) {
            id = "req_" + Ids.newId();
        }
        req.setAttribute(ATTRIBUTE, id);
        res.setHeader(HEADER, id);
        MDC.put("request_id", id);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("request_id");
        }
    }
}
