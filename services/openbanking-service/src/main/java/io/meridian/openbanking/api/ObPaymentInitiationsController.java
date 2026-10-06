package io.meridian.openbanking.api;

import io.meridian.openbanking.api.generated.ObPaymentInitiationsApi;
import io.meridian.openbanking.api.generated.model.ObPaymentInitiation;
import io.meridian.openbanking.api.generated.model.ObPaymentInitiationCreate;
import io.meridian.openbanking.api.generated.model.ObPaymentInitiationsCancelRequest;
import io.meridian.openbanking.application.PaymentInitiationService;
import io.meridian.openbanking.infrastructure.Rows;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** PISP payment initiations: core payment creation under a consent, status refresh, cancellation (all access-logged). */
@RestController
public class ObPaymentInitiationsController implements ObPaymentInitiationsApi {
    private final PaymentInitiationService service;
    private final Actions actions;
    private final TppCalls calls;
    private final Rows rows;

    public ObPaymentInitiationsController(PaymentInitiationService service, Actions actions, TppCalls calls, Rows rows) {
        this.service = service;
        this.actions = actions;
        this.calls = calls;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<ObPaymentInitiation> obPaymentInitiationsCreate(String idempotencyKey, ObPaymentInitiationCreate body) {
        JsonNode req = actions.tree(body);
        return calls.logged(ObConsentsController.uuid(req, "tpp_id"), ObConsentsController.uuid(req, "ob_consent_id"),
                () -> actions.create(idempotencyKey, body, ObPaymentInitiation.class, () -> service.initiate(body)));
    }

    @Override
    public ResponseEntity<ObPaymentInitiation> obPaymentInitiationsRefreshStatus(UUID id, String idempotencyKey) {
        JsonNode row = rows.find("ob_payment_initiation", id).orElse(null);
        return calls.logged(row == null ? null : Rows.uuid(row, "tpp_id"), row == null ? null : Rows.uuid(row, "ob_consent_id"),
                () -> actions.action(idempotencyKey, id, "refresh-status", null, ObPaymentInitiation.class,
                        () -> service.refresh(id)));
    }

    @Override
    public ResponseEntity<ObPaymentInitiation> obPaymentInitiationsCancel(UUID id, String idempotencyKey,
                                                                         ObPaymentInitiationsCancelRequest body) {
        JsonNode row = rows.find("ob_payment_initiation", id).orElse(null);
        return calls.logged(row == null ? null : Rows.uuid(row, "tpp_id"), row == null ? null : Rows.uuid(row, "ob_consent_id"),
                () -> actions.action(idempotencyKey, id, "cancel", body, ObPaymentInitiation.class,
                        () -> service.cancel(id, Http.text(actions.tree(body), "reason"))));
    }
}
