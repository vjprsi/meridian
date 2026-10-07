package io.meridian.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SEC-26: request body cap for every service ({@code meridian.http.max-body-size}, default 16 MB). A declared
 * Content-Length above the cap is answered 413 {@code payload_too_large} before anything is read; a chunked body
 * fails once it passes the cap (the JSON reader then gets an I/O error and the request is rejected).
 */
public class BodySizeFilter extends OncePerRequestFilter {
    private final long max;

    public BodySizeFilter(long max) {
        this.max = max;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (req.getContentLengthLong() > max) {
            res.setStatus(413);
            res.setContentType("application/problem+json");
            res.getWriter().write("{\"type\":\"https://docs.meridian.example/errors/payload_too_large\","
                    + "\"title\":\"Payload too large\",\"status\":413,\"code\":\"payload_too_large\","
                    + "\"detail\":\"Request body exceeds " + max + " bytes\"}");
            return;
        }
        chain.doFilter(req.getContentLengthLong() >= 0 ? req : new Capped(req, max), res);
    }

    /** Wraps a body of unknown length so reading past the cap fails. */
    static final class Capped extends HttpServletRequestWrapper {
        private final long max;

        Capped(HttpServletRequest req, long max) {
            super(req);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();
            return new ServletInputStream() {
                private long count;

                @Override
                public int read() throws IOException {
                    int b = in.read();
                    if (b >= 0 && ++count > max) {
                        throw new IOException("request body exceeds " + max + " bytes");
                    }
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    int n = in.read(buf, off, len);
                    if (n > 0 && (count += n) > max) {
                        throw new IOException("request body exceeds " + max + " bytes");
                    }
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return in.isFinished();
                }

                @Override
                public boolean isReady() {
                    return in.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    in.setReadListener(listener);
                }
            };
        }
    }
}
