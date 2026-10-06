package io.meridian.card;

import io.meridian.card.domain.Luhn;
import io.meridian.common.error.ApiException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stateful stand-ins for everything card-service calls through the mocked ServiceClient: the PCI vault (tokens,
 * PAN de-duplication, Luhn assertion, PIN, reissue), card-tokenization-service status sync, card-authorization-service
 * (authorisations list, usage counters), card-clearing-service fees and ledger-service entries. Every call is
 * recorded so tests can assert what card-service sent.
 */
public class FakePeers {
    public record Call(String service, String method, String path, JsonNode body, String key) {}

    static final class VaultRecord {
        String token;
        String pan;
        String cardId;
        boolean active = true;
        boolean pinSet;
        int pinTries;
    }

    private final JsonMapper json;
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, VaultRecord> byToken = new LinkedHashMap<>();
    private final Map<String, JsonNode> byKey = new HashMap<>();
    /** Every PAN the vault ever received (to prove none leaks into card-service storage). */
    public final Set<String> pansSeen = Collections.synchronizedSet(new java.util.HashSet<>());
    public final List<String> luhnFailures = Collections.synchronizedList(new ArrayList<>());
    /** Forces the next N tokenisations to report a PAN collision. */
    public final AtomicInteger collideNext = new AtomicInteger();
    public final Map<String, ObjectNode> usageByLimit = new HashMap<>();
    public final List<ObjectNode> ledgerEntries = Collections.synchronizedList(new ArrayList<>());

    public FakePeers(JsonMapper json) {
        this.json = json;
    }

    public synchronized JsonNode post(String service, String path, Object body, String key) {
        JsonNode b = body == null ? json.createObjectNode() : json.valueToTree(body);
        calls.add(new Call(service, "POST", path, b, key));
        if (key != null && byKey.containsKey(service + path + key)) {
            return byKey.get(service + path + key);
        }
        JsonNode out = switch (service + " " + path) {
            case "card-vault-service /v1/card-vault" -> tokenize(b);
            case "card-vault-service /v1/card-vault/reissue" -> reissue(b);
            case "card-vault-service /v1/card-vault/pin" -> pin(b);
            case "card-vault-service /v1/card-vault/pin/unblock" -> unblock(b);
            case "card-tokenization-service /v1/card-tokens/card-status-sync" ->
                    json.createObjectNode().put("card_id", b.get("card_id").asString()).put("updated", 0);
            case "card-clearing-service /v1/card-fees" -> {
                ObjectNode fee = ((ObjectNode) b).deepCopy();
                fee.put("id", UUID.randomUUID().toString());
                fee.put("journal_entry_id", UUID.randomUUID().toString());
                yield fee;
            }
            default -> throw new IllegalArgumentException("unexpected POST " + service + " " + path);
        };
        if (key != null) {
            byKey.put(service + path + key, out);
        }
        return out;
    }

    public synchronized JsonNode get(String service, String path) {
        calls.add(new Call(service, "GET", path, null, null));
        if (path.startsWith("/v1/card-authorizations/usage")) {
            ObjectNode o = json.createObjectNode();
            o.put("card_id", path.substring(path.indexOf('=') + 1));
            ArrayNode counters = o.putArray("counters");
            usageByLimit.values().forEach(counters::add);
            return o;
        }
        if (path.startsWith("/v1/card-authorizations?")) {
            String cardId = path.replaceAll(".*card_id=([0-9a-f-]+).*", "$1");
            ObjectNode page = json.createObjectNode();
            ArrayNode data = page.putArray("data");
            data.addObject().put("id", UUID.randomUUID().toString()).put("card_id", cardId)
                    .put("card_program_id", UUID.randomUUID().toString()).put("message_type", "AUTHORIZATION")
                    .put("mti", "0100").put("channel", "POS").put("pos_entry_mode", "CHIP")
                    .put("transaction_amount", "12.50").put("transaction_currency", "EUR")
                    .put("billing_amount", "12.50").put("billing_currency", "EUR").put("status", "APPROVED")
                    .put("decision", "APPROVED").put("response_code", "00").put("card_present", true)
                    .put("cardholder_present", true).put("jit_used", false).put("stand_in", false)
                    .put("is_recurring", false).put("is_international", false).put("cleared_amount", "0")
                    .put("reversed_amount", "0").put("created_at", "2026-10-06T10:00:00Z").put("row_version", 1)
                    .put("updated_at", "2026-10-06T10:00:00Z");
            page.put("has_more", false);
            page.putNull("next_cursor");
            return page;
        }
        if (path.startsWith("/v1/ledger-accounts/") && path.contains("/entries")) {
            ObjectNode page = json.createObjectNode();
            ArrayNode data = page.putArray("data");
            ledgerEntries.forEach(data::add);
            page.put("has_more", false);
            return page;
        }
        throw new IllegalArgumentException("unexpected GET " + service + " " + path);
    }

    // ------------------------------------------------------------------ vault

    private JsonNode tokenize(JsonNode b) {
        String pan = b.get("pan").asString();
        pansSeen.add(pan);
        if (!Luhn.isValid(pan)) {
            luhnFailures.add("not Luhn-valid: length " + pan.length());
            throw ApiException.unprocessable("invalid_pan", "PAN fails the Luhn check");
        }
        if (collideNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw ApiException.conflict("pan_already_vaulted", "PAN already vaulted for another card");
        }
        String cardId = b.get("card_id").asString();
        for (VaultRecord r : byToken.values()) {
            if (r.pan.equals(pan) && r.active) {
                if (r.cardId.equals(cardId)) {
                    return record(r);
                }
                throw ApiException.conflict("pan_already_vaulted", "PAN already vaulted for another card");
            }
        }
        VaultRecord r = new VaultRecord();
        r.token = "tok_" + UUID.randomUUID().toString().replace("-", "");
        r.pan = pan;
        r.cardId = cardId;
        byToken.put(r.token, r);
        return record(r);
    }

    private JsonNode reissue(JsonNode b) {
        VaultRecord src = vault(b.get("pan_token").asString());
        src.active = false;
        VaultRecord r = new VaultRecord();
        r.token = "tok_" + UUID.randomUUID().toString().replace("-", "");
        r.pan = src.pan;
        r.cardId = b.get("card_id").asString();
        r.pinSet = src.pinSet;
        byToken.put(r.token, r);
        return record(r);
    }

    private JsonNode pin(JsonNode b) {
        VaultRecord r = vault(b.get("pan_token").asString());
        String block = b.get("encrypted_pin_block").asString();
        if (block.startsWith("WEAK")) {
            throw ApiException.unprocessable("weak_pin", "PIN is too simple");
        }
        String type = r.pinSet ? "CHANGE" : "SET";
        r.pinSet = true;
        r.pinTries = 0;
        return json.createObjectNode().put("pan_token", r.token).put("pin_set", true).put("event_type", type);
    }

    private JsonNode unblock(JsonNode b) {
        VaultRecord r = vault(b.get("pan_token").asString());
        r.pinTries = 0;
        return json.createObjectNode().put("pan_token", r.token).put("pin_try_count", 0);
    }

    private VaultRecord vault(String token) {
        VaultRecord r = byToken.get(token);
        if (r == null) {
            throw ApiException.notFound("card vault record", token);
        }
        return r;
    }

    public synchronized String panOfToken(String token) {
        return vault(token).pan;
    }

    public synchronized boolean tokenActive(String token) {
        return vault(token).active;
    }

    private ObjectNode record(VaultRecord r) {
        ObjectNode n = json.createObjectNode();
        n.put("id", UUID.nameUUIDFromBytes(r.token.getBytes()).toString());
        n.put("pan_token", r.token);
        n.put("pan_fingerprint", "fp" + Integer.toHexString(r.pan.hashCode()));
        n.put("card_id", r.cardId);
        n.put("bin", r.pan.substring(0, 6));
        n.put("last4", r.pan.substring(r.pan.length() - 4));
        n.put("pin_set", r.pinSet);
        n.put("pin_try_count", r.pinTries);
        n.put("is_active", r.active);
        return n;
    }

    public List<Call> calls(String service, String pathPrefix) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.service().equals(service) && c.path().startsWith(pathPrefix)).toList();
        }
    }
}
