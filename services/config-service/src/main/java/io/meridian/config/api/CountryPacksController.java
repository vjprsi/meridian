package io.meridian.config.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.config.api.generated.CountryPacksApi;
import io.meridian.config.api.generated.model.CountryPacksInstallRequest;
import io.meridian.config.api.generated.model.TenantCountryPack;
import io.meridian.config.application.CountryPackService;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Country packs (platform-global catalogue, read-only) and their installation for a legal entity. */
@RestController
public class CountryPacksController implements CountryPacksApi {
    private final CountryPackService packs;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public CountryPacksController(CountryPackService packs, Idempotency idempotency, JsonMapper json) {
        this.packs = packs;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<TenantCountryPack> countryPacksInstall(UUID id, String idempotencyKey,
                                                                 CountryPacksInstallRequest body) {
        TenantContext.require();
        JsonNode overrides = body.getOverrides() == null ? null : json.valueToTree(body.getOverrides());
        return idempotency.execute(idempotencyKey, Responses.request("install", id, body), TenantCountryPack.class,
                () -> Responses.row(json, HttpStatus.CREATED, packs.install(id, body.getLegalEntityId(), overrides),
                        TenantCountryPack.class));
    }
}
