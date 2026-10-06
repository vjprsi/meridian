package io.meridian.openbanking.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.openbanking.api.generated.ObStandardProfilesApi;
import io.meridian.openbanking.api.generated.model.ObStandardProfile;
import io.meridian.openbanking.api.generated.model.ObStandardProfileCreate;
import io.meridian.openbanking.api.generated.model.ObStandardProfileUpdate;
import io.meridian.openbanking.application.ConsentService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Standard profiles (UK OBIE, Berlin Group, ...): validated configuration and discovery document. */
@RestController
public class ObStandardProfilesController implements ObStandardProfilesApi {
    private final ConsentService consents;
    private final Actions actions;
    private final CrudEngine crud;

    public ObStandardProfilesController(ConsentService consents, Actions actions, CrudEngine crud) {
        this.consents = consents;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<ObStandardProfile> obStandardProfilesCreate(String idempotencyKey, ObStandardProfileCreate body) {
        TenantContext.require();
        consents.validateProfile(actions.tree(body), true);
        return crud.create("ob_standard_profile", ObStandardProfile.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<ObStandardProfile> obStandardProfilesUpdate(UUID id, String ifMatch, ObStandardProfileUpdate body) {
        TenantContext.require();
        consents.validateProfile(actions.tree(body), false);
        return crud.update("ob_standard_profile", ObStandardProfile.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> obStandardProfilesDiscovery(UUID id) {
        TenantContext.require();
        return actions.object(consents.discovery(id));
    }
}
