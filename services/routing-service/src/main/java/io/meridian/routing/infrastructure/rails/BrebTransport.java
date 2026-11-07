package io.meridian.routing.infrastructure.rails;

import io.meridian.web.outbound.OutboundHttp;
import io.meridian.web.outbound.OutboundUrlPolicy;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bre-B (Colombia, Banco de la República instant payments) participant adapter, protocol {@code BREB_JSON_V1}:
 * ISO 20022 content (pacs.008 / pacs.002 / pacs.028, pain.013) carried as JSON over HTTPS to one access node,
 * DICE key directory (llaves: CELULAR, CORREO, DOCUMENTO, ALFANUMERICA, COMERCIO). Every request body is signed
 * (HMAC-SHA256 hex in {@code X-Signature}) with the secret named by {@code config.signing_secret_env} - the secret
 * itself lives in the service environment / secret store, never in the adapter row. Outbound transfers are
 * idempotent on end_to_end_id at the rail, so resending through another node after a timeout cannot pay twice.
 *
 * <p>Built to the Bre-B message model; connection to the real SPBVI / DICE requires the participant's certification
 * against the Banco de la República technical annex (this adapter is exercised against platform/breb-simulator).
 */
@Component
public class BrebTransport implements RailTransport {
    public static final String PROTOCOL = "BREB_JSON_V1";
    static final String DEV_SECRET = "local-dev-breb-secret";
    static final String DEFAULT_SECRET_ENV = "MERIDIAN_BREB_SIGNING_SECRET";

    private final OutboundUrlPolicy policy;
    private final JsonMapper json;
    private final boolean devMode;
    private final Map<Long, OutboundHttp> clients = new ConcurrentHashMap<>();

    public BrebTransport(OutboundUrlPolicy policy, JsonMapper json,
                         @Value("${meridian.security-mode:jwt}") String securityMode) {
        this.policy = policy;
        this.json = json;
        this.devMode = "dev".equalsIgnoreCase(securityMode == null ? "" : securityMode.trim());
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    // ------------------------------------------------------------------ transfers

    @Override
    public Outcome send(Node node, OutboundTransfer t) {
        ObjectNode b = json.createObjectNode();
        b.put("msg_id", t.messageId());
        b.put("end_to_end_id", t.endToEndId());
        b.put("amount", t.amount().setScale(2, RoundingMode.HALF_EVEN).toPlainString());
        b.put("currency", t.currency());
        ObjectNode debtor = b.putObject("debtor");
        debtor.put("name", t.debtorName());
        debtor.put("account_number", t.debtorAccount());
        debtor.put("participant_code", node.participantId());
        ObjectNode creditor = b.putObject("creditor");
        String[] key = creditorKey(t.creditorAccountType(), t.creditorAccount());
        if (key != null) {
            creditor.put("key_type", key[0]);
            creditor.put("key_value", key[1]);
        } else {
            creditor.put("account_number", t.creditorAccount());
            creditor.put("participant_code", t.creditorAgentClearingId());
        }
        if (t.creditorName() != null) {
            creditor.put("name", t.creditorName());
        }
        if (t.remittance() != null) {
            b.put("remittance", t.remittance());
        }
        if (t.purpose() != null) {
            b.put("purpose", t.purpose());
        }
        return exchange(node, "POST", "/spbvi/v1/transfers", b);
    }

    @Override
    public Outcome query(Node node, String endToEndId) {
        return exchange(node, "GET", "/spbvi/v1/transfers/" + enc(endToEndId), null);
    }

    /**
     * Bre-B key of a PROXY creditor: an explicit {@code TYPE:value}, else inferred - {@code @handle} = ALFANUMERICA,
     * an e-mail = CORREO, a Colombian mobile (3xx, optionally +57) = CELULAR, 5-15 digits = DOCUMENTO.
     */
    static String[] creditorKey(String accountType, String account) {
        if (account == null || !"PROXY".equals(accountType)) {
            return null;
        }
        String v = account.trim();
        int c = v.indexOf(':');
        if (c > 0 && v.substring(0, c).matches("[A-Z]{5,12}")) {
            return new String[] {v.substring(0, c), v.substring(c + 1)};
        }
        if (v.startsWith("@")) {
            return new String[] {"ALFANUMERICA", v};
        }
        if (v.contains("@")) {
            return new String[] {"CORREO", v};
        }
        String d = v.replaceAll("[\\s()-]", "");
        if (d.matches("(\\+?57)?3[0-9]{9}")) {
            return new String[] {"CELULAR", d};
        }
        if (d.matches("[0-9]{5,15}")) {
            return new String[] {"DOCUMENTO", d};
        }
        return new String[] {"ALFANUMERICA", v};
    }

    private Outcome exchange(Node node, String method, String path, ObjectNode body) {
        byte[] bytes = body == null ? null : json.writeValueAsBytes(body);
        long start = System.nanoTime();
        try {
            OutboundHttp.Response r = http(node.config()).execute(method, base(node) + path, headers(node, bytes), bytes,
                    bytes == null ? null : "application/json");
            long ms = (System.nanoTime() - start) / 1_000_000;
            String raw = new String(r.body(), StandardCharsets.UTF_8);
            if (r.status() == 200 || r.status() == 201) {
                JsonNode a = json.readTree(raw);
                String st = a.path("status").asString(null);
                if ("ACCP".equals(st) || "RJCT".equals(st)) {
                    return new Outcome(Kind.ANSWERED, st, text(a, "reason_code"), text(a, "reason"),
                            text(a, "clearing_ref"), raw, ms, null);
                }
                if ("PDNG".equals(st) || "ACTC".equals(st)) {
                    // received and in process: reachable, no final answer yet (treated like a timeout, never a failure)
                    return new Outcome(Kind.TIMEOUT, st, null, text(a, "reason"), null, raw, ms, "in_process");
                }
                return new Outcome(Kind.UNREACHABLE, null, null, null, null, raw, ms, "unexpected_answer");
            }
            if (r.status() == 404) {
                return new Outcome(Kind.NOT_FOUND, null, null, null, null, raw, ms, "not_found");
            }
            if (r.status() == 409) {
                return new Outcome(Kind.ANSWERED, "RJCT", "DUPL", "end_to_end_id already used with a different payload",
                        null, raw, ms, null);
            }
            if (r.status() == 422) {
                JsonNode a = parse(raw);
                return new Outcome(Kind.ANSWERED, "RJCT", "FF01", a == null ? "Rejected by the rail as invalid"
                        : text(a, "detail"), null, raw, ms, null);
            }
            return new Outcome(Kind.UNREACHABLE, null, null, null, null, raw, ms, "http_" + r.status());
        } catch (OutboundHttp.Failure f) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            Kind k = OutboundHttp.Failure.CODE_TIMEOUT.equals(f.code()) ? Kind.TIMEOUT : Kind.UNREACHABLE;
            return new Outcome(k, null, null, null, null, null, ms, f.code());
        } catch (OutboundUrlPolicy.RejectedException e) {
            return new Outcome(Kind.UNREACHABLE, null, null, null, null, null, 0, "endpoint_not_allowed");
        }
    }

    // ------------------------------------------------------------------ directory (DICE)

    @Override
    public DirectoryResult directory(Node node, String op, String keyType, String keyValue, JsonNode body) {
        String key = "/dice/v1/keys/" + enc(keyType) + "/" + enc(keyValue);
        String method;
        String path;
        byte[] bytes = null;
        switch (op) {
            case "RESOLVE" -> {
                method = "GET";
                path = key;
            }
            case "DELETE" -> {
                method = "DELETE";
                path = key + "?participant_code=" + enc(node.participantId());
            }
            case "REGISTER" -> {
                method = "POST";
                path = "/dice/v1/keys";
                ObjectNode b = body == null ? json.createObjectNode() : ((ObjectNode) body).deepCopy();
                b.put("key_type", keyType);
                b.put("key_value", keyValue);
                b.put("participant_code", node.participantId());
                b.put("participant_name", node.config().path("participant_name").asString(node.name()));
                bytes = json.writeValueAsBytes(b);
            }
            default -> throw new IllegalArgumentException("unknown directory op " + op);
        }
        try {
            OutboundHttp.Response r = http(node.config()).execute(method, base(node) + path, headers(node, bytes), bytes,
                    bytes == null ? null : "application/json");
            if (r.status() >= 500) {
                return new DirectoryResult(0, null, "http_" + r.status());
            }
            return new DirectoryResult(r.status(), parse(new String(r.body(), StandardCharsets.UTF_8)), null);
        } catch (OutboundHttp.Failure f) {
            return new DirectoryResult(0, null, f.code());
        } catch (OutboundUrlPolicy.RejectedException e) {
            return new DirectoryResult(0, null, "endpoint_not_allowed");
        }
    }

    // ------------------------------------------------------------------ plumbing

    private OutboundHttp http(JsonNode config) {
        long timeout = Math.max(1000, config.path("timeout_ms").asLong(8000));
        return clients.computeIfAbsent(timeout, t -> new OutboundHttp(policy.with(policy.settings()
                .withTimeouts(Duration.ofMillis(Math.min(3000, t)), Duration.ofMillis(t), Duration.ofMillis(t + 2000))
                .withMaxResponseBytes(256 * 1024))));
    }

    private Map<String, String> headers(Node node, byte[] body) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        if (node.participantId() != null) {
            h.put("X-Participant-Id", node.participantId());
        }
        if (body != null) {
            h.put("X-Signature", hmac(secret(node), body));
        }
        return h;
    }

    String secret(Node node) {
        String env = node.config().path("signing_secret_env").asString(DEFAULT_SECRET_ENV);
        String s = System.getenv(env);
        if (s != null && !s.isBlank()) {
            return s;
        }
        if (devMode) {
            return DEV_SECRET;
        }
        throw new IllegalStateException("Bre-B signing secret (environment variable " + env + ") is not set");
    }

    static String hmac(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String base(Node node) {
        String e = node.endpoint();
        return e.endsWith("/") ? e.substring(0, e.length() - 1) : e;
    }

    private JsonNode parse(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : json.readTree(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
