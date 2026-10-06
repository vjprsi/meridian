package io.meridian.cardauthorization.infrastructure;

import io.meridian.cardauthorization.application.Ports;
import io.meridian.cardauthorization.config.AuthProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls a program's JIT funding webhook within its time budget. The body is signed with HMAC-SHA256
 * ({@code X-Signature}); the response is {@code {approved, approved_amount?, decline_reason?}}. Any non-2xx, an
 * unparsable body or a transport failure is ERROR; exceeding the budget is TIMEOUT.
 */
@Component
public class JitHttpClient implements Ports.Jit {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1000))
            .version(HttpClient.Version.HTTP_1_1).build();
    private final JsonMapper json;
    private final AuthProperties props;

    public JitHttpClient(JsonMapper json, AuthProperties props) {
        this.json = json;
        this.props = props;
    }

    @Override
    public Response call(String url, int timeoutMs, JsonNode payload) {
        String body = json.writeValueAsString(payload);
        long t0 = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("X-Signature", sign(body))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            int latency = elapsed(t0);
            if (latency > timeoutMs) {
                return new Response("TIMEOUT", null, r.statusCode(), latency, null, null);
            }
            JsonNode node;
            try {
                node = r.body() == null || r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
            } catch (RuntimeException e) {
                return new Response("ERROR", null, r.statusCode(), latency, null, null);
            }
            if (r.statusCode() / 100 != 2) {
                return new Response("ERROR", null, r.statusCode(), latency, truncate(node), null);
            }
            boolean approved = node.path("approved").asBoolean(false);
            BigDecimal amount = node.hasNonNull("approved_amount") ? new BigDecimal(node.get("approved_amount").asString()) : null;
            return new Response(approved ? "APPROVED" : "DECLINED", amount, r.statusCode(), latency, truncate(node),
                    node.hasNonNull("decline_reason") ? node.get("decline_reason").asString() : null);
        } catch (HttpTimeoutException e) {
            return new Response("TIMEOUT", null, null, elapsed(t0), null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response("ERROR", null, null, elapsed(t0), null, null);
        } catch (Exception e) {
            return new Response("ERROR", null, null, elapsed(t0), null, null);
        }
    }

    private JsonNode truncate(JsonNode node) {
        String s = json.writeValueAsString(node);
        if (s.length() <= 2000) {
            return node;
        }
        return json.createObjectNode().put("truncated", s.substring(0, 2000));
    }

    String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.jitSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int elapsed(long t0) {
        return (int) ((System.nanoTime() - t0) / 1_000_000);
    }
}
