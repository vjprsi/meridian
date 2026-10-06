package io.meridian.fx.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.fx.api.generated.RemittancePartnersApi;
import io.meridian.fx.api.generated.model.RemittancePartner;
import io.meridian.fx.api.generated.model.RemittancePartnerCreate;
import io.meridian.fx.api.generated.model.RemittancePartnerUpdate;
import io.meridian.fx.application.CatalogService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Remittance payout partners: generic CRUD with country / payout method validation. */
@RestController
public class RemittancePartnersController implements RemittancePartnersApi {
    static final String TABLE = "remittance_partner";
    private final CatalogService catalog;
    private final CrudEngine crud;
    private final Actions actions;

    public RemittancePartnersController(CatalogService catalog, CrudEngine crud, Actions actions) {
        this.catalog = catalog;
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<RemittancePartner> remittancePartnersCreate(String idempotencyKey, RemittancePartnerCreate body) {
        TenantContext.require();
        catalog.validatePartner(actions.tree(body), true);
        return crud.create(TABLE, RemittancePartner.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<RemittancePartner> remittancePartnersUpdate(UUID id, String ifMatch, RemittancePartnerUpdate body) {
        TenantContext.require();
        catalog.validatePartner(actions.tree(body), false);
        return crud.update(TABLE, RemittancePartner.class, body, ifMatch, Crud.params().with("id", id));
    }
}
