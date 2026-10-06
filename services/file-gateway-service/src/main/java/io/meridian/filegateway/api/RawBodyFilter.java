package io.meridian.filegateway.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * Keeps a copy of JSON request bodies on file endpoints. The generated request models only carry the
 * contract's columns, so fields the contract cannot express (file {@code content}, {@code content_base64},
 * {@code content_url}, outbound {@code records}, write-only secret references) are read from the raw body.
 */
@Component
public class RawBodyFilter extends OncePerRequestFilter {
    static final int LIMIT = 64 * 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String m = request.getMethod();
        return !request.getRequestURI().startsWith("/v1/files/") || (!"POST".equals(m) && !"PATCH".equals(m));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new ContentCachingRequestWrapper(req, LIMIT), res);
    }
}
