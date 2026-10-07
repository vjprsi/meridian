package io.meridian.cardauthorization.infrastructure;

import io.meridian.cardauthorization.application.Ports;
import io.meridian.cardauthorization.config.AuthProperties;
import io.meridian.web.outbound.OutboundHttp;
import io.meridian.web.outbound.OutboundUrlPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls a program's JIT funding webhook within its time budget. The body is signed with HMAC-SHA256
 * ({@code X-Signature}); the response is {@code {approved, approved_amount?, decline_reason?}}. Any non-2xx, an
 * unparsable body or a transport failure is ERROR; exceeding the budget is TIMEOUT.
 *
 * <p>The tenant-configured URL goes through the shared {@link OutboundUrlPolicy} (SEC-18): https only, public
 * addresses only (pinned at connect time), no redirects, bounded response size.
 */
@Component
public class JitHttpClient implements Ports.Jit, DisposableBean {
    private final OutboundHttp http;
    private final JsonMapper json;
    private final AuthProperties props;

    public JitHttpClient(JsonMapper json, AuthProperties props, OutboundUrlPolicy policy) {
        this.json = json;
        this.props = props;
        this.http = new OutboundHttp(policy.with(policy.settings()
                .withTimeouts(Duration.ofMillis(1000), Duration.ofSeconds(10), Duration.ofSeconds(10))
                .withMaxResponseBytes(64 * 1024)));
    }

    @Override
    public void destroy() throws Exception {
        http.close();
    }

    @Override
    public Response call(String url, int timeoutMs, JsonNode payload) {
        String body = json.writeValueAsString(payload);
        long t0 = System.nanoTime();
        try {
            OutboundHttp.Response r = http.execute("POST", url, Map.of("X-Signature", sign(body)),
                    body.getBytes(StandardCharsets.UTF_8), "application/json", Duration.ofMillis(timeoutMs));
            int latency = elapsed(t0);
            if (latency > timeoutMs) {
                return new Response("TIMEOUT", null, r.status(), latency, null, null);
            }
            String text = new String(r.body(), StandardCharsets.UTF_8);
            JsonNode node;
            try {
                node = r.truncated() ? null : text.isBlank() ? json.createObjectNode() : json.readTree(text);
            } catch (RuntimeException e) {
                node = null;
            }
            if (node == null) {
                return new Response("ERROR", null, r.status(), latency, null, null);
            }
            if (r.status() / 100 != 2) {
                return new Response("ERROR", null, r.status(), latency, truncate(node), null);
            }
            boolean approved = node.path("approved").asBoolean(false);
            BigDecimal amount = node.hasNonNull("approved_amount") ? new BigDecimal(node.get("approved_amount").asString()) : null;
            return new Response(approved ? "APPROVED" : "DECLINED", amount, r.status(), latency, truncate(node),
                    node.hasNonNull("decline_reason") ? node.get("decline_reason").asString() : null);
        } catch (OutboundHttp.Failure e) {
            return new Response(OutboundHttp.Failure.CODE_TIMEOUT.equals(e.code()) ? "TIMEOUT" : "ERROR", null, null,
                    elapsed(t0), null, null);
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
