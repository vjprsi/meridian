package io.meridian.tenant.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.api.generated.BrandingApi;
import io.meridian.tenant.api.generated.model.TenantBranding;
import io.meridian.tenant.api.generated.model.TenantBrandingCreate;
import io.meridian.tenant.api.generated.model.TenantBrandingUpdate;
import io.meridian.tenant.application.BrandingService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** White-label branding per channel; validation of colours/locales/URLs and a rendered preview. */
@RestController
public class BrandingController implements BrandingApi {
    private final BrandingService branding;
    private final CrudEngine crud;

    public BrandingController(BrandingService branding, CrudEngine crud) {
        this.branding = branding;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<TenantBranding> brandingCreate(String idempotencyKey, TenantBrandingCreate body) {
        TenantContext.require();
        branding.validate(body, null);
        return crud.create("tenant_branding", TenantBranding.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<TenantBranding> brandingUpdate(UUID id, String ifMatch, TenantBrandingUpdate body) {
        TenantContext.require();
        branding.validate(body, id);
        return crud.update("tenant_branding", TenantBranding.class, body, ifMatch, Crud.params().with("id", id));
    }

    /** Contract declares no response schema; returns tokens, sample screens and accessibility warnings. */
    @Override
    public ResponseEntity<Void> brandingPreview(UUID id) {
        TenantContext.require();
        return Responses.untyped(ResponseEntity.ok(branding.preview(id)));
    }
}
