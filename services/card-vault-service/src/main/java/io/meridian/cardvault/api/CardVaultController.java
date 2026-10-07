package io.meridian.cardvault.api;

import io.meridian.cardvault.api.generated.CardVaultApi;
import io.meridian.cardvault.api.generated.model.CardDataVault;
import io.meridian.cardvault.api.generated.model.CardDataVaultCreate;
import io.meridian.cardvault.api.generated.model.CardVaultLookupRequest;
import io.meridian.cardvault.api.generated.model.CardVaultPinRequest;
import io.meridian.cardvault.api.generated.model.CardVaultPinUnblockRequest;
import io.meridian.cardvault.api.generated.model.CardVaultReissueRequest;
import io.meridian.cardvault.api.generated.model.CardVaultRevealRequest;
import io.meridian.cardvault.api.generated.model.CardVaultRotateKeysRequest;
import io.meridian.cardvault.api.generated.model.CardVaultVerifyRequest;
import io.meridian.cardvault.application.AccessLog;
import io.meridian.cardvault.application.VaultService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.error.ApiException;
import io.meridian.web.idempotency.Idempotency;
import io.meridian.web.security.RequiredScope;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Card vault API. Writes that do not return card data (tokenize, PIN set/unblock, reissue, rotate-keys) are
 * exactly-once per Idempotency-Key; their idempotency fingerprints carry keyed hashes, never PAN / CVV / PIN blocks.
 * Reveal, lookup and verify are detokenising reads: each call is audited on its own and their responses (which may
 * hold card data) are deliberately NOT stored in the idempotency table, so the key is required but not replayed.
 * Access beyond the generated {@code cards:write} is enforced by {@link VaultAccessPolicy} (SEC-11): dedicated
 * scopes for reveal ({@code cards:pan_reveal}) and vault administration ({@code cards:vault_admin}), recent step-up
 * for user tokens, service-only lookup / verify / PIN set. Refused reveals are audited too.
 */
@RestController
public class CardVaultController implements CardVaultApi {
    static final String CALLER_HEADER = "X-Caller-Service";

    private final VaultService vault;
    private final Idempotency idempotency;
    private final JsonMapper json;
    private final HttpServletRequest request;
    private final VaultAccessPolicy access;
    private final AccessLog accessLog;

    public CardVaultController(VaultService vault, Idempotency idempotency, JsonMapper json, HttpServletRequest request,
                               VaultAccessPolicy access, AccessLog accessLog) {
        this.access = access;
        this.accessLog = accessLog;
        this.vault = vault;
        this.idempotency = idempotency;
        this.json = json;
        this.request = request;
    }

    @Override
    public ResponseEntity<CardDataVault> cardVaultCreate(String idempotencyKey, CardDataVaultCreate body) {
        access.require(VaultAccessPolicy.Operation.TOKENIZE);
        JsonNode req = tree(body);
        VaultService.Tokenize t = new VaultService.Tokenize(Http.text(req, "pan"),
                Http.integer(req, "expiry_month"), Http.integer(req, "expiry_year"), Http.uuid(req, "card_id"));
        Map<String, Object> fp = fingerprint("tokenize");
        fp.put("pan", vault.fingerprint(t.pan()));
        fp.put("expiry", t.expiryMonth() + "/" + t.expiryYear());
        fp.put("card_id", String.valueOf(t.cardId()));
        return idempotency.execute(idempotencyKey, fp, CardDataVault.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(model(vault.tokenize(t), CardDataVault.class)));
    }

    @Override
    @RequiredScope(VaultAccessPolicy.SCOPE_REVEAL)
    public ResponseEntity<CardDataVault> cardVaultReveal(String idempotencyKey, CardVaultRevealRequest body) {
        JsonNode req = tree(body);
        try {
            access.require(VaultAccessPolicy.Operation.REVEAL);
        } catch (ApiException denied) {
            auditDenied(Http.text(req, "purpose"), req, denied);
            throw denied;
        }
        return ResponseEntity.ok(model(vault.reveal(Http.uuid(req, "card_id"), Http.text(req, "purpose"),
                Http.text(req, "public_key"), caller()), CardDataVault.class));
    }

    @Override
    public ResponseEntity<CardDataVault> cardVaultLookup(String idempotencyKey, CardVaultLookupRequest body) {
        access.require(VaultAccessPolicy.Operation.LOOKUP);
        JsonNode req = tree(body);
        return ResponseEntity.ok(model(vault.lookup(Http.text(req, "pan"), Http.text(req, "purpose"), caller()),
                CardDataVault.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> cardVaultVerify(String idempotencyKey, CardVaultVerifyRequest body) {
        access.require(VaultAccessPolicy.Operation.VERIFY);
        JsonNode req = tree(body);
        return ResponseEntity.ok(vault.verify(new VaultService.Verify(Http.text(req, "pan_token"), Http.text(req, "cvv"),
                Http.integer(req, "expiry_month"), Http.integer(req, "expiry_year"),
                Http.text(req, "encrypted_pin_block"), Http.text(req, "pin_key_id")), caller()));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ResponseEntity<Map<String, Object>> cardVaultPin(String idempotencyKey, CardVaultPinRequest body) {
        access.require(VaultAccessPolicy.Operation.PIN_SET);
        JsonNode req = tree(body);
        String token = Http.text(req, "pan_token");
        String block = Http.text(req, "encrypted_pin_block");
        String keyId = Http.text(req, "pin_key_id");
        Map<String, Object> fp = fingerprint("pin");
        fp.put("pan_token", token);
        fp.put("pin_block", vault.fingerprint(block));
        fp.put("pin_key_id", keyId);
        ResponseEntity raw = idempotency.execute(idempotencyKey, fp, Map.class,
                () -> ResponseEntity.ok(vault.setPin(token, block, keyId, caller())));
        return (ResponseEntity<Map<String, Object>>) raw;
    }

    @Override
    @RequiredScope(VaultAccessPolicy.SCOPE_ADMIN)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ResponseEntity<Map<String, Object>> cardVaultPinUnblock(String idempotencyKey, CardVaultPinUnblockRequest body) {
        access.require(VaultAccessPolicy.Operation.PIN_UNBLOCK);
        JsonNode req = tree(body);
        Map<String, Object> fp = fingerprint("pin-unblock");
        fp.put("body", req);
        ResponseEntity raw = idempotency.execute(idempotencyKey, fp, Map.class,
                () -> ResponseEntity.ok(vault.unblockPin(Http.text(req, "pan_token"), caller())));
        return (ResponseEntity<Map<String, Object>>) raw;
    }

    @Override
    public ResponseEntity<CardDataVault> cardVaultReissue(String idempotencyKey, CardVaultReissueRequest body) {
        access.require(VaultAccessPolicy.Operation.REISSUE);
        JsonNode req = tree(body);
        Map<String, Object> fp = fingerprint("reissue");
        fp.put("body", req);
        return idempotency.execute(idempotencyKey, fp, CardDataVault.class,
                () -> ResponseEntity.ok(model(vault.reissue(Http.text(req, "pan_token"), Http.uuid(req, "card_id"),
                        Http.integer(req, "expiry_month"), Http.integer(req, "expiry_year"), caller()),
                        CardDataVault.class)));
    }

    @Override
    @RequiredScope(VaultAccessPolicy.SCOPE_ADMIN)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ResponseEntity<Map<String, Object>> cardVaultRotateKeys(String idempotencyKey, CardVaultRotateKeysRequest body) {
        access.require(VaultAccessPolicy.Operation.ROTATE_KEYS);
        JsonNode req = tree(body);
        Map<String, Object> fp = fingerprint("rotate-keys");
        fp.put("body", req);
        ResponseEntity raw = idempotency.execute(idempotencyKey, fp, Map.class,
                () -> ResponseEntity.ok(vault.rotate(Http.text(req, "key_type"), caller())));
        return (ResponseEntity<Map<String, Object>>) raw;
    }

    /** A refused reveal is recorded in the PCI access log (best effort: never masks the authorisation error). */
    private void auditDenied(String purpose, JsonNode req, ApiException denied) {
        if (purpose == null || purpose.isBlank()) {
            return;
        }
        try {
            String card = Http.text(req, "card_id");
            accessLog.failure(caller(), purpose, null, card == null ? null : UUID.fromString(card),
                    "denied: " + denied.code());
        } catch (RuntimeException ignored) {
            // the 401/403 is the outcome that matters
        }
    }

    /**
     * SEC-32: the calling service comes from the authenticated service identity (X-Meridian-Service, honoured only
     * with an internal token; dev mode: the header) or the relaying client ({@code azp}); a client-chosen
     * X-Caller-Service header is no longer believed.
     */
    AccessLog.Caller caller() {
        String svc = TenantContext.callingService().or(TenantContext::via).orElse(null);
        String principal = TenantContext.principal();
        return new AccessLog.Caller(svc == null || svc.isBlank() ? principal : svc, principal, request.getRemoteAddr());
    }

    private static Map<String, Object> fingerprint(String action) {
        TenantContext.require();
        Map<String, Object> fp = new LinkedHashMap<>();
        fp.put("action", action);
        return fp;
    }

    private JsonNode tree(Object body) {
        return body == null ? json.createObjectNode() : json.valueToTree(body);
    }

    private <T> T model(JsonNode row, Class<T> type) {
        return json.treeToValue(row, type);
    }

    /** Snake_case field access on request bodies. */
    static final class Http {
        private Http() {}

        static String text(JsonNode n, String f) {
            JsonNode v = n == null ? null : n.get(f);
            return v == null || v.isNull() ? null : v.asString();
        }

        static Integer integer(JsonNode n, String f) {
            JsonNode v = n == null ? null : n.get(f);
            return v == null || v.isNull() ? null : v.asInt();
        }

        static UUID uuid(JsonNode n, String f) {
            String s = text(n, f);
            return s == null ? null : UUID.fromString(s);
        }
    }
}
