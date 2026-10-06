package io.meridian.openbanking.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.openbanking.api.generated.TppsApi;
import io.meridian.openbanking.api.generated.model.Tpp;
import io.meridian.openbanking.api.generated.model.TppCertificatePage;
import io.meridian.openbanking.api.generated.model.TppCreate;
import io.meridian.openbanking.api.generated.model.TppUpdate;
import io.meridian.openbanking.api.generated.model.TppsReactivateRequest;
import io.meridian.openbanking.api.generated.model.TppsRegisterRequest;
import io.meridian.openbanking.api.generated.model.TppsSuspendRequest;
import io.meridian.openbanking.application.TppService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** TPP registry: onboarding, dynamic client registration, verification and access suspension. */
@RestController
public class TppsController implements TppsApi {
    private final TppService service;
    private final Actions actions;
    private final CrudEngine crud;

    public TppsController(TppService service, Actions actions, CrudEngine crud) {
        this.service = service;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<Tpp> tppsCreate(String idempotencyKey, TppCreate body) {
        return actions.create(idempotencyKey, body, Tpp.class, () -> service.create(body));
    }

    @Override
    public ResponseEntity<Tpp> tppsUpdate(UUID id, String ifMatch, TppUpdate body) {
        TenantContext.require();
        JsonNode req = actions.tree(body);
        service.validateUpdate(req);
        return crud.update("tpp", Tpp.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Tpp> tppsRegister(String idempotencyKey, TppsRegisterRequest body) {
        JsonNode req = actions.tree(body);
        return actions.create(idempotencyKey, body, Tpp.class,
                () -> service.register(Http.text(req, "software_statement"), Http.text(req, "certificate_pem")));
    }

    @Override
    public ResponseEntity<Tpp> tppsVerify(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "verify", null, Tpp.class, () -> service.verify(id));
    }

    @Override
    public ResponseEntity<Tpp> tppsSuspend(UUID id, String idempotencyKey, TppsSuspendRequest body) {
        return actions.action(idempotencyKey, id, "suspend", body, Tpp.class,
                () -> service.suspend(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<Tpp> tppsReactivate(UUID id, String idempotencyKey, TppsReactivateRequest body) {
        return actions.action(idempotencyKey, id, "reactivate", body, Tpp.class,
                () -> service.reactivate(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<TppCertificatePage> tppsCertificates(UUID id, Integer limit, String cursor) {
        crud.get("tpp", Tpp.class, Crud.params().with("id", id));
        return crud.list("tpp_certificate", TppCertificatePage.class,
                Crud.params().with("tpp_id", id).with("limit", limit).with("cursor", cursor));
    }
}
