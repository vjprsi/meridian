package io.meridian.webhook.infrastructure;

import io.meridian.web.outbound.OutboundHttp;
import io.meridian.web.outbound.OutboundUrlPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Outbound HTTP to client endpoints (SEC-09): POST JSON through the shared {@link OutboundUrlPolicy} - https only,
 * every resolved address must be public and the connection is pinned to it (no DNS rebinding), no redirects, bounded
 * timeouts and response size.
 *
 * <p>What is kept for the delivery log is deliberately small: the status code, at most {@value #SNIPPET_CHARS}
 * printable characters of the response (control characters removed) and a coarse error category
 * ({@code destination_not_allowed}, {@code timeout}, {@code connect_failed}, {@code tls_error}, {@code io_error})
 * instead of exception messages, so the attempts API is neither a data read-back channel nor a port-scan oracle.
 */
@Component
public class WebhookHttpSender implements DisposableBean {
    public record Result(Integer statusCode, String responseBody, String error, long latencyMs) {}

    static final int SNIPPET_CHARS = 256;

    private final OutboundHttp http;

    @Autowired
    public WebhookHttpSender(OutboundUrlPolicy policy,
                             @Value("${meridian.webhooks.timeout-ms:10000}") long timeoutMs,
                             @Value("${meridian.webhooks.allow-insecure-localhost:false}") boolean allowInsecureLocalhost) {
        this(new OutboundHttp(senderPolicy(policy, timeoutMs, allowInsecureLocalhost)));
    }

    /** http://localhost receivers only in dev mode (ignored otherwise); response read capped at 4 KB. */
    static OutboundUrlPolicy senderPolicy(OutboundUrlPolicy policy, long timeoutMs, boolean allowInsecureLocalhost) {
        OutboundUrlPolicy p = policy.relaxedForDev(allowInsecureLocalhost, allowInsecureLocalhost);
        return p.with(p.settings()
                .withTimeouts(Duration.ofSeconds(5), Duration.ofMillis(timeoutMs), Duration.ofMillis(timeoutMs))
                .withMaxResponseBytes(4096));
    }

    WebhookHttpSender(OutboundHttp http) {
        this.http = http;
    }

    public Result post(String url, Map<String, String> headers, String body) {
        long start = System.nanoTime();
        try {
            OutboundHttp.Response r = http.post(url, headers, body.getBytes(StandardCharsets.UTF_8), "application/json");
            return new Result(r.status(), snippet(new String(r.body(), StandardCharsets.UTF_8)), null, elapsed(start));
        } catch (OutboundUrlPolicy.RejectedException e) {
            return new Result(null, null, e.code(), elapsed(start));
        } catch (OutboundHttp.Failure e) {
            return new Result(null, null, e.code(), elapsed(start));
        } catch (IllegalArgumentException e) {
            return new Result(null, null, "invalid_request", elapsed(start));
        }
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** Printable characters only, whitespace collapsed, at most {@value #SNIPPET_CHARS} characters; null if empty. */
    static String snippet(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder b = new StringBuilder(Math.min(s.length(), SNIPPET_CHARS));
        boolean space = false;
        for (int i = 0; i < s.length() && b.length() < SNIPPET_CHARS; i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                space = b.length() > 0;
                continue;
            }
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                    || Character.getType(c) == Character.UNASSIGNED || Character.isSurrogate(c)) {
                continue;
            }
            if (space) {
                b.append(' ');
                space = false;
            }
            b.append(c);
        }
        String out = b.length() > SNIPPET_CHARS ? b.substring(0, SNIPPET_CHARS) : b.toString();
        return out.isEmpty() ? null : out;
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    @Override
    public void destroy() throws Exception {
        http.close();
    }
}
