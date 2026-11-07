package io.meridian.brebsim;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The whole simulated rail: directory, transfers, request-to-pay and node control (in memory). */
@RestController
public class BrebSimController {
    private static final String COP = "COP";

    /** One registered key: who holds it and where it pays to. */
    record Key(String type, String value, String participant, String participantName, String accountNumber,
               String accountType, String holderName, String documentType, String documentNumber, String keyId) {}

    /** A received transfer: its payload hash (idempotency) and the pacs.002 answer. */
    record Transfer(String hash, Map<String, Object> answer) {}

    private final Map<String, Key> keys = new ConcurrentHashMap<>();
    private final Map<String, Transfer> transfers = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> rtps = new ConcurrentHashMap<>();
    private final Set<String> downNodes = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicLong> nodeHits = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(100000);
    private final JsonMapper json;
    private final String secret;
    private final long slowMillis;

    public BrebSimController(JsonMapper json, @Value("${breb.sim.secret:local-dev-breb-secret}") String secret,
                             @Value("${breb.sim.slow-millis:25000}") long slowMillis) {
        this.json = json;
        this.secret = secret;
        this.slowMillis = slowMillis;
    }

    // ------------------------------------------------------------------ node gate + signature

    private ResponseEntity<Map<String, Object>> gate(String node, String body, String signature) {
        nodeHits.computeIfAbsent(node, n -> new AtomicLong()).incrementAndGet();
        if (downNodes.contains(node)) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "NODE_UNAVAILABLE", "Access node " + node + " is down");
        }
        if (body != null && !hmac(body).equals(signature)) {
            return error(HttpStatus.UNAUTHORIZED, "SIGNATURE_INVALID", "X-Signature does not match the body");
        }
        return null;
    }

    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("detail", detail);
        return ResponseEntity.status(status).body(m);
    }

    private static String id(String type, String value) {
        return type + ":" + value;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    // ------------------------------------------------------------------ DICE: key directory

    @PostMapping("/nodes/{node}/dice/v1/keys")
    public ResponseEntity<Map<String, Object>> register(@PathVariable String node, @RequestBody String body,
                                                        @RequestHeader(value = "X-Signature", required = false) String sig) {
        ResponseEntity<Map<String, Object>> g = gate(node, body, sig);
        if (g != null) {
            return g;
        }
        JsonNode r = json.readTree(body);
        String type = text(r, "key_type");
        String value = BrebKeys.normalise(type, text(r, "key_value"));
        if (value == null) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "KEY_INVALID", "Invalid " + type + " key value");
        }
        String participant = text(r, "participant_code");
        if (participant == null || text(r, "account_number") == null || text(r, "holder_name") == null) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "FIELDS_REQUIRED",
                    "participant_code, account_number and holder_name are required");
        }
        Key existing = keys.get(id(type, value));
        if (existing != null && !existing.participant().equals(participant)) {
            return error(HttpStatus.CONFLICT, "KEY_IN_USE",
                    "Key is registered at another participant; request portability with the holder's consent");
        }
        Key k = new Key(type, value, participant, text(r, "participant_name"), text(r, "account_number"),
                text(r, "account_type"), text(r, "holder_name"), text(r, "document_type"), text(r, "document_number"),
                existing != null ? existing.keyId() : "LL" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        keys.put(id(type, value), k);
        return ResponseEntity.status(existing == null ? HttpStatus.CREATED : HttpStatus.OK).body(keyView(k, false));
    }

    @GetMapping("/nodes/{node}/dice/v1/keys/{type}/{value}")
    public ResponseEntity<Map<String, Object>> resolve(@PathVariable String node, @PathVariable String type,
                                                       @PathVariable String value) {
        ResponseEntity<Map<String, Object>> g = gate(node, null, null);
        if (g != null) {
            return g;
        }
        String v = BrebKeys.normalise(type, value);
        if (v == null) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "KEY_INVALID", "Invalid " + type + " key value");
        }
        Key k = keys.get(id(type, v));
        return k == null ? error(HttpStatus.NOT_FOUND, "KEY_NOT_FOUND", "No participant holds this key")
                : ResponseEntity.ok(keyView(k, true));
    }

    @DeleteMapping("/nodes/{node}/dice/v1/keys/{type}/{value}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String node, @PathVariable String type,
                                                      @PathVariable String value, @RequestParam String participant_code) {
        ResponseEntity<Map<String, Object>> g = gate(node, null, null);
        if (g != null) {
            return g;
        }
        String v = BrebKeys.normalise(type, value);
        Key k = v == null ? null : keys.get(id(type, v));
        if (k == null) {
            return error(HttpStatus.NOT_FOUND, "KEY_NOT_FOUND", "No participant holds this key");
        }
        if (!k.participant().equals(participant_code)) {
            return error(HttpStatus.FORBIDDEN, "NOT_KEY_HOLDER", "Only the participant holding the key can delete it");
        }
        keys.remove(id(type, v));
        Map<String, Object> m = keyView(k, false);
        m.put("status", "ELIMINADA");
        return ResponseEntity.ok(m);
    }

    /** Portability: the new participant takes over a key (holder consent evidenced by the caller). */
    @PostMapping("/nodes/{node}/dice/v1/keys/{type}/{value}/portability")
    public ResponseEntity<Map<String, Object>> port(@PathVariable String node, @PathVariable String type,
                                                    @PathVariable String value, @RequestBody String body,
                                                    @RequestHeader(value = "X-Signature", required = false) String sig) {
        ResponseEntity<Map<String, Object>> g = gate(node, body, sig);
        if (g != null) {
            return g;
        }
        JsonNode r = json.readTree(body);
        String v = BrebKeys.normalise(type, value);
        Key k = v == null ? null : keys.get(id(type, v));
        if (k == null) {
            return error(HttpStatus.NOT_FOUND, "KEY_NOT_FOUND", "No participant holds this key");
        }
        if (text(r, "consent_reference") == null) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "CONSENT_REQUIRED", "consent_reference is required for portability");
        }
        Key ported = new Key(type, v, text(r, "participant_code"), text(r, "participant_name"), text(r, "account_number"),
                text(r, "account_type"), k.holderName(), k.documentType(), k.documentNumber(), k.keyId());
        keys.put(id(type, v), ported);
        Map<String, Object> m = keyView(ported, false);
        m.put("previous_participant_code", k.participant());
        return ResponseEntity.ok(m);
    }

    private Map<String, Object> keyView(Key k, boolean resolve) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key_id", k.keyId());
        m.put("key_type", k.type());
        m.put("key_value", k.value());
        m.put("participant_code", k.participant());
        m.put("participant_name", k.participantName());
        m.put("account_type", k.accountType());
        m.put("masked_name", BrebKeys.mask(k.holderName()));
        if (resolve) {
            // the account itself is not disclosed to the payer's institution: a directory reference routes the credit
            m.put("account_ref", "REF" + Integer.toHexString((k.participant() + k.accountNumber()).hashCode()).toUpperCase());
        }
        m.put("status", "ACTIVA");
        return m;
    }

    // ------------------------------------------------------------------ SPBVI: transfers

    @PostMapping("/nodes/{node}/spbvi/v1/transfers")
    public ResponseEntity<Map<String, Object>> transfer(@PathVariable String node, @RequestBody String body,
                                                        @RequestHeader(value = "X-Signature", required = false) String sig)
            throws InterruptedException {
        ResponseEntity<Map<String, Object>> g = gate(node, body, sig);
        if (g != null) {
            return g;
        }
        JsonNode r = json.readTree(body);
        String e2e = text(r, "end_to_end_id");
        if (e2e == null || e2e.isBlank()) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "E2E_REQUIRED", "end_to_end_id is required");
        }
        String hash = sha(body.replaceAll("\"msg_id\"\\s*:\\s*\"[^\"]*\"", ""));
        Transfer prior = transfers.get(e2e);
        if (prior != null) {
            if (!prior.hash().equals(hash)) {
                return error(HttpStatus.CONFLICT, "DUPLICATE_E2E", "end_to_end_id already used with a different payload");
            }
            Map<String, Object> replay = new LinkedHashMap<>(prior.answer());
            replay.put("replayed", true);
            return ResponseEntity.ok(replay);
        }
        Map<String, Object> answer = decide(r, e2e, node);
        BigDecimal amount = new BigDecimal(String.valueOf(text(r, "amount") == null ? "0" : text(r, "amount")));
        if (cents(amount) == 88) {
            // still processing at the rail: a status query meanwhile answers PDNG (received, no final answer yet)
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("end_to_end_id", e2e);
            pending.put("status", "PDNG");
            pending.put("reason", "In process at the receiving participant");
            transfers.put(e2e, new Transfer(hash, pending));
            Thread.sleep(slowMillis);
        }
        transfers.put(e2e, new Transfer(hash, answer));
        if (cents(amount) == 77) {
            Thread.sleep(slowMillis); // answered after the client timeout; the status query sees the outcome
        }
        return ResponseEntity.ok(answer);
    }

    private Map<String, Object> decide(JsonNode r, String e2e, String node) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("message_type", "pacs.002");
        a.put("end_to_end_id", e2e);
        a.put("original_msg_id", text(r, "msg_id"));
        a.put("node", node);
        String currency = text(r, "currency");
        BigDecimal amount;
        try {
            amount = new BigDecimal(text(r, "amount"));
        } catch (Exception e) {
            return reject(a, "AM12", "Invalid amount");
        }
        if (!COP.equals(currency)) {
            return reject(a, "AM03", "Bre-B settles COP only");
        }
        if (amount.signum() <= 0 || amount.scale() > 2) {
            return reject(a, "AM12", "Invalid amount");
        }
        JsonNode creditor = r.path("creditor");
        String keyType = text(creditor, "key_type");
        if (keyType != null) {
            String v = BrebKeys.normalise(keyType, text(creditor, "key_value"));
            Key k = v == null ? null : keys.get(id(keyType, v));
            if (k == null) {
                return reject(a, "AC03", "Creditor key not found in DICE");
            }
            a.put("creditor_participant_code", k.participant());
        } else if (text(creditor, "account_number") == null) {
            return reject(a, "AC03", "Creditor key or account required");
        }
        int c = cents(amount);
        if (c == 13) {
            return reject(a, "AM02", "Amount not allowed by the creditor institution");
        }
        if (c == 66) {
            return reject(a, "AC04", "Creditor account closed");
        }
        a.put("status", "ACCP");
        a.put("clearing_ref", "BREB" + seq.incrementAndGet());
        a.put("accepted_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        a.put("settlement", "RTGS_REAL_TIME");
        return a;
    }

    private static Map<String, Object> reject(Map<String, Object> a, String code, String reason) {
        a.put("status", "RJCT");
        a.put("reason_code", code);
        a.put("reason", reason);
        return a;
    }

    private static int cents(BigDecimal amount) {
        return amount.movePointRight(2).remainder(BigDecimal.valueOf(100)).abs().intValue();
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** pacs.028 status query: the stored pacs.002, or 404 when the rail never received the transfer. */
    @GetMapping("/nodes/{node}/spbvi/v1/transfers/{e2e}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String node, @PathVariable String e2e) {
        ResponseEntity<Map<String, Object>> g = gate(node, null, null);
        if (g != null) {
            return g;
        }
        Transfer t = transfers.get(e2e);
        return t == null ? error(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "No transfer with this end_to_end_id")
                : ResponseEntity.ok(t.answer());
    }

    // ------------------------------------------------------------------ request to pay

    @PostMapping("/nodes/{node}/spbvi/v1/rtp")
    public ResponseEntity<Map<String, Object>> rtp(@PathVariable String node, @RequestBody String body,
                                                   @RequestHeader(value = "X-Signature", required = false) String sig) {
        ResponseEntity<Map<String, Object>> g = gate(node, body, sig);
        if (g != null) {
            return g;
        }
        JsonNode r = json.readTree(body);
        String type = text(r, "payer_key_type");
        String v = BrebKeys.normalise(type, text(r, "payer_key_value"));
        if (v == null || !keys.containsKey(id(type, v))) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "PAYER_KEY_NOT_FOUND", "The payer key is not registered");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        String rid = "RTP" + seq.incrementAndGet();
        m.put("rtp_id", rid);
        m.put("message_type", "pain.013");
        m.put("payer_masked_name", BrebKeys.mask(keys.get(id(type, v)).holderName()));
        m.put("amount", text(r, "amount"));
        m.put("currency", COP);
        m.put("reference", text(r, "reference"));
        m.put("status", "PENDIENTE");
        rtps.put(rid, m);
        return ResponseEntity.status(HttpStatus.CREATED).body(m);
    }

    @GetMapping("/nodes/{node}/spbvi/v1/rtp/{id}")
    public ResponseEntity<Map<String, Object>> rtpStatus(@PathVariable String node, @PathVariable String id) {
        ResponseEntity<Map<String, Object>> g = gate(node, null, null);
        if (g != null) {
            return g;
        }
        Map<String, Object> m = rtps.get(id);
        return m == null ? error(HttpStatus.NOT_FOUND, "RTP_NOT_FOUND", "Unknown request to pay") : ResponseEntity.ok(m);
    }

    // ------------------------------------------------------------------ test control

    @PostMapping("/sim/nodes/{node}/down")
    public Map<String, Object> down(@PathVariable String node) {
        downNodes.add(node);
        return Map.of("node", node, "status", "DOWN");
    }

    @PostMapping("/sim/nodes/{node}/up")
    public Map<String, Object> up(@PathVariable String node) {
        downNodes.remove(node);
        return Map.of("node", node, "status", "UP");
    }

    @GetMapping("/sim/stats")
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keys", keys.size());
        m.put("transfers", transfers.size());
        m.put("down_nodes", downNodes);
        Map<String, Long> hits = new LinkedHashMap<>();
        nodeHits.forEach((k, v) -> hits.put(k, v.get()));
        m.put("node_hits", hits);
        return m;
    }

    @PostMapping("/sim/reset")
    public Map<String, Object> reset() {
        keys.clear();
        transfers.clear();
        rtps.clear();
        downNodes.clear();
        nodeHits.clear();
        return Map.of("reset", true);
    }

    @GetMapping("/nodes/{node}/health")
    public ResponseEntity<Map<String, Object>> health(@PathVariable String node) {
        return downNodes.contains(node) ? error(HttpStatus.SERVICE_UNAVAILABLE, "NODE_UNAVAILABLE", node + " down")
                : ResponseEntity.ok(Map.of("node", node, "status", "UP"));
    }
}
