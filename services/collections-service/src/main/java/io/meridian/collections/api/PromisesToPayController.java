package io.meridian.collections.api;

import io.meridian.collections.api.generated.PromisesToPayApi;
import io.meridian.collections.api.generated.model.PromiseToPay;
import io.meridian.collections.api.generated.model.PromiseToPayUpdate;
import io.meridian.collections.api.generated.model.PromisesToPayCancelRequest;
import io.meridian.collections.application.ArrangementService;
import io.meridian.collections.infrastructure.Rows;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Promises to pay: amount and date are fixed once made (cancel and record a new promise to change them). */
@RestController
public class PromisesToPayController implements PromisesToPayApi {
    private static final Set<String> PROTECTED = Set.of("collection_case_id", "promised_amount", "promised_date", "currency");

    private final ArrangementService arrangements;
    private final Actions actions;
    private final CrudEngine crud;
    private final Rows rows;

    public PromisesToPayController(ArrangementService arrangements, Actions actions, CrudEngine crud, Rows rows) {
        this.arrangements = arrangements;
        this.actions = actions;
        this.crud = crud;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<PromiseToPay> promisesToPayUpdate(UUID id, String ifMatch, PromiseToPayUpdate body) {
        TenantContext.require();
        ObjectNodeGuard.notTerminal(rows, "promise_to_pay", id, Set.of("KEPT", "PARTIALLY_KEPT", "BROKEN", "CANCELLED"),
                "promise_resolved");
        return actions.guardedUpdate(crud, "promise_to_pay", PromiseToPay.class, id, ifMatch, body, PROTECTED,
                "cancel the promise and record a new one");
    }

    @Override
    public ResponseEntity<PromiseToPay> promisesToPayCancel(UUID id, String idempotencyKey, PromisesToPayCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, PromiseToPay.class,
                () -> arrangements.cancelPromise(id, Http.text(actions.tree(body), "reason")));
    }
}
