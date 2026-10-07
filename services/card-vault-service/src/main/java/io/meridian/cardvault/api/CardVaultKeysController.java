package io.meridian.cardvault.api;

import io.meridian.cardvault.api.generated.CardVaultKeysApi;
import io.meridian.cardvault.api.generated.model.CardVaultKey;
import io.meridian.cardvault.api.generated.model.CardVaultKeyCreate;
import io.meridian.cardvault.api.generated.model.CardVaultKeysRewrapRequest;
import io.meridian.cardvault.application.KeyService;
import io.meridian.cardvault.application.VaultService;
import io.meridian.web.security.RequiredScope;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Vault key metadata. Create registers a key: material, version and KCV come from the HSM / KMS adapter, the new key
 * becomes the active key of its type (previous ones are retired). List / get are generic (the wrapped data key is
 * never returned). Re-wrap is the per-tenant master key rotation (GAP-01), vault administrators or services only.
 */
@RestController
public class CardVaultKeysController implements CardVaultKeysApi {
    private final KeyService keys;
    private final Idempotency idempotency;
    private final CrudEngine crud;
    private final JsonMapper json;
    private final VaultService vault;
    private final VaultAccessPolicy access;
    private final CardVaultController calls;

    public CardVaultKeysController(KeyService keys, Idempotency idempotency, CrudEngine crud, JsonMapper json,
                                   VaultService vault, VaultAccessPolicy access, CardVaultController calls) {
        this.keys = keys;
        this.idempotency = idempotency;
        this.crud = crud;
        this.json = json;
        this.vault = vault;
        this.access = access;
        this.calls = calls;
    }

    @Override
    @RequiredScope(VaultAccessPolicy.SCOPE_ADMIN)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ResponseEntity<Map<String, Object>> cardVaultKeysRewrap(String idempotencyKey, CardVaultKeysRewrapRequest body) {
        access.require(VaultAccessPolicy.Operation.REWRAP);
        TenantContext.require();
        java.util.LinkedHashMap<String, Object> fp = new java.util.LinkedHashMap<>();
        fp.put("action", "rewrap");
        fp.put("body", body == null ? json.createObjectNode() : json.valueToTree(body));
        ResponseEntity raw = idempotency.execute(idempotencyKey, fp, Map.class,
                () -> ResponseEntity.ok(vault.rewrapMasterKey(calls.caller())));
        return (ResponseEntity<Map<String, Object>>) raw;
    }

    @Override
    public ResponseEntity<CardVaultKey> cardVaultKeysCreate(String idempotencyKey, CardVaultKeyCreate body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, req, CardVaultKey.class, () -> {
            String retire = CardVaultController.Http.text(req, "retire_after");
            KeyService.Key k = keys.create(CardVaultController.Http.text(req, "key_type"),
                    CardVaultController.Http.text(req, "hsm_key_label"), CardVaultController.Http.text(req, "algorithm"),
                    retire == null ? null : OffsetDateTime.parse(retire));
            String row = crud.findOne("card_vault_key", Map.of("id", k.id())).orElseThrow();
            return ResponseEntity.status(HttpStatus.CREATED).body(crud.read(row, CardVaultKey.class));
        });
    }
}
