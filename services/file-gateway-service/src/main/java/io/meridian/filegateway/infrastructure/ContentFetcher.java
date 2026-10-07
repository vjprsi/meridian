package io.meridian.filegateway.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.outbound.OutboundHttp;
import io.meridian.web.outbound.OutboundUrlPolicy;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fetches file content from a partner URL (e.g. a pre-signed object URL), bounded in size and time (SEC-08).
 *
 * <p>The URL must satisfy the shared {@link OutboundUrlPolicy}: https only, and the host must resolve to public
 * addresses only (no loopback, private, link-local / cloud-metadata, CGNAT or ULA targets); the connection is pinned
 * to the validated addresses and redirects are refused, not followed. Error responses report only the HTTP status or
 * a coarse failure category - never the remote body or internal network details.
 */
@Component
public class ContentFetcher implements DisposableBean {
    private final OutboundHttp http;
    private final long maxBytes;

    @Autowired
    public ContentFetcher(OutboundUrlPolicy policy, @Value("${meridian.files.max-size-bytes:26214400}") long maxBytes,
                          @Value("${meridian.files.fetch-timeout-ms:60000}") long timeoutMs) {
        this(new OutboundHttp(policy.with(policy.settings()
                .withTimeouts(Duration.ofSeconds(5), Duration.ofMillis(timeoutMs), Duration.ofMillis(timeoutMs))
                .withMaxResponseBytes(maxBytes + 1))), maxBytes);
    }

    ContentFetcher(OutboundHttp http, long maxBytes) {
        this.http = http;
        this.maxBytes = maxBytes;
    }

    public byte[] fetch(String url) {
        OutboundHttp.Response r;
        try {
            r = http.get(url, Map.of("Accept", "*/*"));
        } catch (OutboundUrlPolicy.RejectedException e) {
            throw ApiException.unprocessable("invalid_content_url", "content_url is not allowed: " + e.getMessage());
        } catch (OutboundHttp.Failure e) {
            if (OutboundHttp.Failure.CODE_BLOCKED.equals(e.code())) {
                throw ApiException.unprocessable("invalid_content_url", "content_url is not allowed: " + e.getMessage());
            }
            throw new ApiException(502, "content_fetch_failed", "Cannot fetch content_url (" + e.code() + ")");
        }
        if (r.status() >= 300 && r.status() < 400) {
            throw new ApiException(502, "content_fetch_failed",
                    "Fetching content_url returned a redirect (HTTP " + r.status() + "); redirects are not followed");
        }
        if (r.status() < 200 || r.status() >= 300) {
            throw new ApiException(502, "content_fetch_failed", "Fetching content_url returned HTTP " + r.status());
        }
        if (r.truncated() || r.body().length > maxBytes) {
            throw ApiException.unprocessable("file_too_large", "File exceeds " + maxBytes + " bytes");
        }
        return r.body();
    }

    @Override
    public void destroy() throws Exception {
        http.close();
    }
}
