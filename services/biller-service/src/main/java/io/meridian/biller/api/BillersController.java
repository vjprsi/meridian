package io.meridian.biller.api;

import io.meridian.biller.api.generated.BillersApi;
import io.meridian.biller.api.generated.model.BillInquiry;
import io.meridian.biller.api.generated.model.Biller;
import io.meridian.biller.api.generated.model.BillerCreate;
import io.meridian.biller.api.generated.model.BillerProductPage;
import io.meridian.biller.api.generated.model.BillerUpdate;
import io.meridian.biller.api.generated.model.BillersSuspendRequest;
import io.meridian.biller.api.generated.model.BillersValidateReferenceRequest;
import io.meridian.biller.application.CatalogService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Biller catalogue: definitions, status changes, products and account reference validation. */
@RestController
public class BillersController implements BillersApi {
    private static final Set<String> PROTECTED = Set.of("biller_code", "currency");

    private final CatalogService catalog;
    private final CrudEngine crud;
    private final Actions actions;

    public BillersController(CatalogService catalog, CrudEngine crud, Actions actions) {
        this.catalog = catalog;
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<Biller> billersCreate(String idempotencyKey, BillerCreate body) {
        TenantContext.require();
        CatalogService.validateBiller(actions.tree(body));
        return crud.create("biller", Biller.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<Biller> billersUpdate(UUID id, String ifMatch, BillerUpdate body) {
        TenantContext.require();
        ObjectNode merged = (ObjectNode) actions.tree(crud.get("biller", Biller.class, Crud.params().with("id", id)).getBody());
        actions.tree(body).properties().forEach(e -> {
            if (!e.getValue().isNull()) {
                merged.set(e.getKey(), e.getValue());
            }
        });
        CatalogService.validateBiller(merged);
        return actions.guardedUpdate(crud, "biller", Biller.class, id, ifMatch, body, PROTECTED,
                "the code and currency of a biller are fixed; create a new biller instead");
    }

    @Override
    public ResponseEntity<Biller> billersActivate(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "activate", null, Biller.class, () -> catalog.setStatus(id, "ACTIVE", null));
    }

    @Override
    public ResponseEntity<Biller> billersSuspend(UUID id, String idempotencyKey, BillersSuspendRequest body) {
        return actions.action(idempotencyKey, id, "suspend", body, Biller.class,
                () -> catalog.setStatus(id, "SUSPENDED", Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<BillerProductPage> billersProducts(UUID id, Integer limit, String cursor) {
        crud.get("biller", Biller.class, Crud.params().with("id", id));
        return crud.list("biller_product", BillerProductPage.class,
                Crud.params().with("biller_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<BillInquiry> billersValidateReference(UUID id, String idempotencyKey,
                                                                BillersValidateReferenceRequest body) {
        return actions.action(idempotencyKey, id, "validate-reference", body, BillInquiry.class,
                () -> catalog.inquire(id, Http.text(actions.tree(body), "account_reference"), false));
    }
}
