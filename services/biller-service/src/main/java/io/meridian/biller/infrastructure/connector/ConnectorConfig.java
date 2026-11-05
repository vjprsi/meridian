package io.meridian.biller.infrastructure.connector;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * Typed, defaulted view over a biller's {@code connection_config} JSON (stored as {@code jsonb}, so it arrives as a
 * nested object on the biller row). Shape (all fields optional except {@code base_url}, which is what activates a
 * real connector):
 *
 * <pre>
 * {
 *   "base_url": "https://api.partner.example.com",
 *   "endpoints":       { "inquiry": "/inquiries", "pay": "/payments", "topup": "/topups" },
 *   "auth":            { "type": "api_key", "header": "X-Api-Key", "secret_ref": "vault:biller/acme#api-key" },
 *   "timeout_ms":      8000,
 *   "retry":           { "max_attempts": 3, "backoff_ms": 200 },
 *   "circuit_breaker": { "failure_threshold": 5, "cooldown_ms": 30000 },
 *   "request":         { "reference_field": "accountRef", "amount_field": "amount", "extra": { "channel": "WALLET" } },
 *   "response":        { "code_path": "status", "success_values": ["00","APPROVED"], "reference_path": "data.txnId" }
 * }
 * </pre>
 *
 * Credentials are never stored inline in production: {@code auth.secret_ref} is a reference resolved by
 * {@link BillerSecrets}.
 */
public final class ConnectorConfig {
    private final JsonNode cfg;

    public ConnectorConfig(JsonNode biller) {
        JsonNode c = biller == null ? null : biller.get("connection_config");
        this.cfg = c == null || c.isNull() || !c.isObject() ? MissingNode.getInstance() : c;
    }

    /** A real connector is configured only when a usable base URL is present. */
    public boolean present() {
        return baseUrl() != null;
    }

    public String baseUrl() {
        String b = text(cfg, "base_url");
        if (b == null || b.isBlank()) {
            return null;
        }
        b = b.trim();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    public String endpoint(String name, String dflt) {
        JsonNode e = cfg.path("endpoints").get(name);
        return e == null || e.isNull() ? dflt : e.asString();
    }

    public int timeoutMs(int dflt) {
        return intAt(cfg, "timeout_ms", dflt);
    }

    public int retryMaxAttempts(int dflt) {
        return intAt(cfg.path("retry"), "max_attempts", dflt);
    }

    public long retryBackoffMs(long dflt) {
        return longAt(cfg.path("retry"), "backoff_ms", dflt);
    }

    public int breakerThreshold(int dflt) {
        return intAt(cfg.path("circuit_breaker"), "failure_threshold", dflt);
    }

    public long breakerCooldownMs(long dflt) {
        return longAt(cfg.path("circuit_breaker"), "cooldown_ms", dflt);
    }

    public JsonNode auth() {
        return cfg.path("auth");
    }

    public JsonNode request() {
        return cfg.path("request");
    }

    public JsonNode response() {
        return cfg.path("response");
    }

    /** The raw config node (for connector-specific keys such as Claro's {@code distributor_id}). */
    public JsonNode node() {
        return cfg;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static int intAt(JsonNode n, String f, int dflt) {
        JsonNode v = n.path(f);
        if (v.isNull() || v.isMissingNode()) {
            return dflt;
        }
        if (v.isNumber()) {
            return v.intValue();
        }
        try {
            return Integer.parseInt(v.asString().trim());
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    private static long longAt(JsonNode n, String f, long dflt) {
        JsonNode v = n.path(f);
        if (v.isNull() || v.isMissingNode()) {
            return dflt;
        }
        if (v.isNumber()) {
            return v.longValue();
        }
        try {
            return Long.parseLong(v.asString().trim());
        } catch (RuntimeException e) {
            return dflt;
        }
    }
}
