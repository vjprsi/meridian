package io.meridian.openbanking.api;

import io.meridian.openbanking.api.generated.ObConsentsApi;
import io.meridian.openbanking.api.generated.model.ObAccessLogPage;
import io.meridian.openbanking.api.generated.model.ObAuthorisation;
import io.meridian.openbanking.api.generated.model.ObConsent;
import io.meridian.openbanking.api.generated.model.ObConsentAccountPage;
import io.meridian.openbanking.api.generated.model.ObConsentCreate;
import io.meridian.openbanking.api.generated.model.ObConsentsAuthoriseRequest;
import io.meridian.openbanking.api.generated.model.ObConsentsRevokeRequest;
import io.meridian.openbanking.application.ConsentService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Consents: request, SCA authorisation start, revocation, expiry, selected accounts and access log. */
@RestController
public class ObConsentsController implements ObConsentsApi {
    private final ConsentService service;
    private final Actions actions;
    private final CrudEngine crud;
    private final TppCalls calls;

    public ObConsentsController(ConsentService service, Actions actions, CrudEngine crud, TppCalls calls) {
        this.service = service;
        this.actions = actions;
        this.crud = crud;
        this.calls = calls;
    }

    @Override
    public ResponseEntity<ObConsent> obConsentsCreate(String idempotencyKey, ObConsentCreate body) {
        JsonNode req = actions.tree(body);
        return calls.logged(uuid(req, "tpp_id"), null,
                () -> actions.create(idempotencyKey, body, ObConsent.class, () -> service.create(body)));
    }

    @Override
    public ResponseEntity<ObAuthorisation> obConsentsAuthorise(UUID id, String idempotencyKey, ObConsentsAuthoriseRequest body) {
        return actions.action(idempotencyKey, id, "authorise", body, ObAuthorisation.class,
                () -> service.authorise(id, actions.tree(body)));
    }

    @Override
    public ResponseEntity<ObConsent> obConsentsRevoke(UUID id, String idempotencyKey, ObConsentsRevokeRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "revoke", body, ObConsent.class,
                () -> service.revoke(id, Http.text(req, "revoked_by"), Http.text(req, "reason")));
    }

    @Override
    public ResponseEntity<Map<String, Object>> obConsentsExpireDue(String idempotencyKey) {
        return actions.object(idempotencyKey, "expire-due", null, service::expireDue);
    }

    @Override
    public ResponseEntity<ObConsentAccountPage> obConsentsAccounts(UUID id, Integer limit, String cursor) {
        crud.get("ob_consent", ObConsent.class, Crud.params().with("id", id));
        return crud.list("ob_consent_account", ObConsentAccountPage.class,
                Crud.params().with("ob_consent_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<ObAccessLogPage> obConsentsAccessLog(UUID id, Integer limit, String cursor) {
        crud.get("ob_consent", ObConsent.class, Crud.params().with("id", id));
        return crud.list("ob_access_log", ObAccessLogPage.class,
                Crud.params().with("ob_consent_id", id).with("limit", limit).with("cursor", cursor));
    }

    static UUID uuid(JsonNode n, String f) {
        String s = Http.text(n, f);
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
