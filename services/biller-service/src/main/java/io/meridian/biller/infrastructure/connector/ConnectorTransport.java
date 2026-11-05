package io.meridian.biller.infrastructure.connector;

import java.time.Duration;
import java.util.Map;

/**
 * Transport seam the REST biller connectors call. The production binding ({@link OutboundTransport}) routes through
 * the shared {@link io.meridian.web.outbound.OutboundHttp} (SEC-18 policy: https only, public addresses pinned at
 * connect time, no redirects, bounded response); unit tests supply a stub so the connector / resilience logic is
 * exercised with no network.
 */
public interface ConnectorTransport {

    /** An answered exchange: HTTP status and the (bounded) response body. */
    record Response(int status, byte[] body, boolean truncated) {}

    /**
     * A request that never produced an HTTP response. {@code retryable} is true for transient faults (read timeout,
     * connection failure) that a retry may clear, false for faults a retry cannot fix (policy-blocked destination,
     * TLS failure, bad URL).
     */
    final class Failure extends RuntimeException {
        private final boolean retryable;

        public Failure(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    /** Sends one request within {@code timeout}. Returns the answered {@link Response} or throws {@link Failure}. */
    Response send(String method, String url, Map<String, String> headers, byte[] body, String contentType,
                  Duration timeout);
}
