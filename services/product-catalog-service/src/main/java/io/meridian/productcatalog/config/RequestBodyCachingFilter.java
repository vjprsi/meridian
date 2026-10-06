package io.meridian.productcatalog.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * Keeps a copy of JSON POST bodies so pricing actions can read optional context objects ({@code applicant},
 * {@code context}) that the generated request models do not declare.
 */
@Component
public class RequestBodyCachingFilter extends OncePerRequestFilter {
    static final int LIMIT = 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String type = req.getContentType();
        if ("POST".equals(req.getMethod()) && type != null && type.contains("json")) {
            chain.doFilter(new ContentCachingRequestWrapper(req, LIMIT), res);
        } else {
            chain.doFilter(req, res);
        }
    }
}
