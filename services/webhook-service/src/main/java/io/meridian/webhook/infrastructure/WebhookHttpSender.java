package io.meridian.webhook.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Outbound HTTP to client endpoints: POST JSON, no redirects, bounded timeouts, response body truncated to 4 KB. */
@Component
public class WebhookHttpSender {
    public record Result(Integer statusCode, String responseBody, String error, long latencyMs) {}

    private final HttpClient http;
    private final Duration timeout;

    public WebhookHttpSender(@Value("${meridian.webhooks.timeout-ms:10000}") long timeoutMs) {
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Result post(String url, Map<String, String> headers, String body) {
        long start = System.nanoTime();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(b::header);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Result(r.statusCode(), truncate(r.body(), 4000), null, elapsed(start));
        } catch (IOException | IllegalArgumentException e) {
            return new Result(null, null, truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), 500), elapsed(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(null, null, "interrupted", elapsed(start));
        }
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
