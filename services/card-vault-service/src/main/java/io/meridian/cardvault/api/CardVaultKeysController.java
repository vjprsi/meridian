package io.meridian.cardvault.api;

import io.meridian.cardvault.api.generated.CardVaultKeysApi;
import io.meridian.cardvault.api.generated.model.CardVaultKey;
import io.meridian.cardvault.api.generated.model.CardVaultKeyCreate;
import io.meridian.cardvault.application.KeyService;
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
 * Vault key metadata. Create registers an HSM key: version and KCV come from the HSM, the new key becomes the
 * active key of its type (previous ones are retired). List / get are generic.
 */
@RestController
public class CardVaultKeysController implements CardVaultKeysApi {
    private final KeyService keys;
    private final Idempotency idempotency;
    private final CrudEngine crud;
    private final JsonMapper json;

    public CardVaultKeysController(KeyService keys, Idempotency idempotency, CrudEngine crud, JsonMapper json) {
        this.keys = keys;
        this.idempotency = idempotency;
        this.crud = crud;
        this.json = json;
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
