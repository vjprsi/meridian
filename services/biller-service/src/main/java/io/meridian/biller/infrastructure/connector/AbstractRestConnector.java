package io.meridian.biller.infrastructure.connector;

import io.meridian.biller.application.BillerGateway;
import io.meridian.biller.infrastructure.Rows;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * Base for REST biller connectors. It wraps the {@link ConnectorTransport} with real resilience and maps everything
 * onto the existing {@link BillerGateway.Outcome} contract:
 * <ul>
 *   <li><b>connect / read timeouts</b> — from the transport, plus a per-call total deadline derived from
 *       {@code connection_config.timeout_ms};</li>
 *   <li><b>bounded retry with exponential backoff</b> — on read timeouts, connection faults and HTTP 5xx, up to
 *       {@code retry.max_attempts}; retries reuse the same {@code Idempotency-Key}, so they are safe;</li>
 *   <li><b>per-provider circuit breaker</b> — {@link CircuitBreaker}; while open, calls are short-circuited.</li>
 * </ul>
 * A definite provider answer becomes SUCCESS or REJECTED (the subclass decides from the body); anything that leaves
 * the result unknown — a read timeout, 5xx after exhausting retries, a non-retryable transport failure that is
 * nonetheless ambiguous, or an open breaker — becomes TIMEOUT, so the caller keeps the payer's funds reserved and
 * retries later rather than wrongly rejecting the payment. A clearly fatal transport failure (policy-blocked URL,
 * TLS error) becomes REJECTED, since a retry cannot help.
 */
abstract class AbstractRestConnector implements BillerConnector {
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final ConnectorTransport transport;
    protected final CircuitBreakerRegistry breakers;
    protected final BillerSecrets secrets;
    protected final JsonMapper json;

    protected AbstractRestConnector(ConnectorTransport transport, CircuitBreakerRegistry breakers,
                                    BillerSecrets secrets, JsonMapper json) {
        this.transport = transport;
        this.breakers = breakers;
        this.secrets = secrets;
        this.json = json;
    }

    /** Result of the resilient exchange: an HTTP answer, or a terminal classification. */
    protected sealed interface Exchange permits Answered, Unanswered {}

    protected record Answered(int status, JsonNode body) implements Exchange {}

    /** No usable answer: {@code timeout} true &rarr; keep funds held (TIMEOUT); false &rarr; hard failure (REJECTED). */
    protected record Unanswered(boolean timeout, String message) implements Exchange {}

    /**
     * Executes one logical provider call with retry, backoff and the circuit breaker. {@code idempotencyKey} must be
     * stable across retries of the same logical attempt (so the provider deduplicates) and change only when the
     * caller genuinely re-attempts the payment.
     */
    protected Exchange call(ConnectorConfig cfg, String providerKey, String idempotencyKey, String method, String url,
                            Map<String, String> headers, JsonNode body) {
        CircuitBreaker breaker = breakers.breaker(providerKey, cfg.breakerThreshold(5), cfg.breakerCooldownMs(30_000));
        if (breaker.shouldShortCircuit()) {
            log.warn("biller connector circuit OPEN for {} — short-circuiting {} {}", providerKey, method, url);
            return new Unanswered(true, "Provider temporarily unavailable (circuit open)");
        }
        int maxAttempts = Math.max(1, cfg.retryMaxAttempts(3));
        long backoff = cfg.retryBackoffMs(200);
        Duration timeout = Duration.ofMillis(Math.max(1, cfg.timeoutMs(8_000)));
        byte[] payload = body == null ? null : json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        Map<String, String> hs = new LinkedHashMap<>();
        hs.put("Accept", "application/json");
        if (idempotencyKey != null) {
            hs.put("Idempotency-Key", idempotencyKey);
        }
        if (headers != null) {
            hs.putAll(headers);
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ConnectorTransport.Response r = transport.send(method, url, hs, payload,
                        payload == null ? null : "application/json", timeout);
                if (r.status() >= 500) {
                    breaker.recordFailure();
                    if (attempt < maxAttempts) {
                        backoff(backoff, attempt);
                        continue;
                    }
                    return new Unanswered(true, "Provider returned HTTP " + r.status());
                }
                breaker.recordSuccess();
                return new Answered(r.status(), parse(r));
            } catch (ConnectorTransport.Failure f) {
                breaker.recordFailure();
                if (!f.retryable()) {
                    log.warn("biller connector {} fatal transport failure: {}", providerKey, f.getMessage());
                    return new Unanswered(false, "Provider call failed: " + f.getMessage());
                }
                if (attempt < maxAttempts) {
                    backoff(backoff, attempt);
                }
            }
        }
        return new Unanswered(true, "Provider did not answer in time");
    }

    private JsonNode parse(ConnectorTransport.Response r) {
        if (r.truncated()) {
            return json.createObjectNode().put("_truncated", true);
        }
        String s = r.body() == null ? "" : new String(r.body(), StandardCharsets.UTF_8);
        if (s.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(s);
        } catch (RuntimeException e) {
            return json.createObjectNode().put("_unparsable", true);
        }
    }

    private static void backoff(long backoffMs, int attempt) {
        if (backoffMs <= 0) {
            return;
        }
        long delay = Math.min(backoffMs * (1L << (attempt - 1)), 5_000L);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ shared helpers

    /** Builds the auth headers described by {@code connection_config.auth}; the secret is resolved by reference. */
    protected Map<String, String> authHeaders(ConnectorConfig cfg) {
        JsonNode auth = cfg.auth();
        String type = auth.path("type").asString("none").trim().toLowerCase(Locale.ROOT);
        String secret = secrets.resolve(auth);
        Map<String, String> h = new LinkedHashMap<>();
        switch (type) {
            case "bearer", "oauth2", "token" -> {
                if (secret != null) {
                    h.put("Authorization", "Bearer " + secret);
                }
            }
            case "api_key", "apikey" -> {
                if (secret != null) {
                    h.put(auth.path("header").asString("X-Api-Key"), secret);
                }
            }
            case "basic" -> {
                if (secret != null) {
                    h.put("Authorization", "Basic "
                            + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
                }
            }
            default -> { /* no auth header */ }
        }
        return h;
    }

    /** A stable per-provider key for the circuit breaker: aggregator if set, else biller code, else biller id. */
    protected static String providerKey(JsonNode biller) {
        String agg = Rows.str(biller, "aggregator_name");
        if (agg != null && !agg.isBlank()) {
            return "aggregator:" + agg.trim().toLowerCase(Locale.ROOT);
        }
        String code = Rows.str(biller, "biller_code");
        if (code != null && !code.isBlank()) {
            return "biller:" + code.trim().toLowerCase(Locale.ROOT);
        }
        return "biller:" + Rows.str(biller, "id");
    }

    /** Navigates a dotted path ({@code a.b.c}) into a JSON object; never null. */
    protected static JsonNode at(JsonNode node, String path) {
        if (node == null || path == null || path.isBlank()) {
            return MissingNode.getInstance();
        }
        JsonNode cur = node;
        for (String seg : path.split("\\.")) {
            cur = cur.path(seg);
        }
        return cur;
    }

    protected static String textAt(JsonNode node, String path) {
        JsonNode n = at(node, path);
        return n.isMissingNode() || n.isNull() ? null : n.asString();
    }

    protected static BigDecimal decAt(JsonNode node, String path) {
        JsonNode n = at(node, path);
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        try {
            return n.isNumber() ? n.decimalValue() : new BigDecimal(n.asString().trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    protected static LocalDate dateAt(JsonNode node, String path) {
        String s = textAt(node, path);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** First non-null text among the given keys, read directly off the node (no dotted paths). */
    protected static String firstText(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull() && !v.isMissingNode()) {
                return v.asString();
            }
        }
        return null;
    }
}
