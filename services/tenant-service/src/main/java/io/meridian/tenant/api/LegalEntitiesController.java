package io.meridian.tenant.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.api.generated.LegalEntitiesApi;
import io.meridian.tenant.api.generated.model.LegalEntity;
import io.meridian.tenant.api.generated.model.LegalEntityCreate;
import io.meridian.tenant.api.generated.model.LegalEntityUpdate;
import io.meridian.tenant.application.OrganizationService;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Legal entities of the tenant: LEI/BIC validation, group structure, lifecycle and events. */
@RestController
public class LegalEntitiesController implements LegalEntitiesApi {
    private final OrganizationService organization;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public LegalEntitiesController(OrganizationService organization, Idempotency idempotency, JsonMapper json) {
        this.organization = organization;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<LegalEntity> legalEntitiesCreate(String idempotencyKey, LegalEntityCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, LegalEntity.class,
                () -> Responses.of(HttpStatus.CREATED, organization.createLegalEntity(body), LegalEntity.class, json));
    }

    @Override
    public ResponseEntity<LegalEntity> legalEntitiesUpdate(UUID id, String ifMatch, LegalEntityUpdate body) {
        TenantContext.require();
        return organization.updateLegalEntity(id, body, ifMatch, LegalEntity.class);
    }
}
