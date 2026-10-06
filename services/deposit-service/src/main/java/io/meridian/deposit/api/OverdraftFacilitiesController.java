package io.meridian.deposit.api;

import io.meridian.deposit.api.generated.OverdraftFacilitiesApi;
import io.meridian.deposit.api.generated.model.OverdraftFacilitiesApproveRequest;
import io.meridian.deposit.api.generated.model.OverdraftFacilitiesCancelRequest;
import io.meridian.deposit.api.generated.model.OverdraftFacilitiesChangeLimitRequest;
import io.meridian.deposit.api.generated.model.OverdraftFacilitiesSuspendRequest;
import io.meridian.deposit.api.generated.model.OverdraftFacility;
import io.meridian.deposit.api.generated.model.OverdraftFacilityCreate;
import io.meridian.deposit.api.generated.model.OverdraftFacilityUpdate;
import io.meridian.deposit.application.OverdraftService;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Overdraft facilities: request, credit approval (four-eyes), suspend, cancel, limit changes. */
@RestController
public class OverdraftFacilitiesController implements OverdraftFacilitiesApi {
    private final OverdraftService overdrafts;
    private final Actions actions;
    private final CrudEngine crud;

    public OverdraftFacilitiesController(OverdraftService overdrafts, Actions actions, CrudEngine crud) {
        this.overdrafts = overdrafts;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<OverdraftFacility> overdraftFacilitiesCreate(String idempotencyKey, OverdraftFacilityCreate body) {
        return actions.create(idempotencyKey, body, OverdraftFacility.class, () -> overdrafts.request(body));
    }

    @Override
    public ResponseEntity<OverdraftFacility> overdraftFacilitiesUpdate(UUID id, String ifMatch, OverdraftFacilityUpdate body) {
        return actions.guardedUpdate(crud, "overdraft_facility", OverdraftFacility.class, id, ifMatch, body,
                Set.of("limit_amount", "currency", "deposit_account_id", "overdraft_type"), "use change-limit");
    }

    @Override
    public ResponseEntity<OverdraftFacility> overdraftFacilitiesApprove(UUID id, String idempotencyKey,
                                                                       OverdraftFacilitiesApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, OverdraftFacility.class,
                () -> overdrafts.approve(id, Http.text(actions.tree(body), "comment"), idempotencyKey));
    }

    @Override
    public ResponseEntity<OverdraftFacility> overdraftFacilitiesSuspend(UUID id, String idempotencyKey,
                                                                       OverdraftFacilitiesSuspendRequest body) {
        return actions.action(idempotencyKey, id, "suspend", body, OverdraftFacility.class,
                () -> overdrafts.suspend(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<OverdraftFacility> overdraftFacilitiesCancel(UUID id, String idempotencyKey,
                                                                      OverdraftFacilitiesCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, OverdraftFacility.class,
                () -> overdrafts.cancel(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<OverdraftFacility> overdraftFacilitiesChangeLimit(UUID id, String idempotencyKey,
                                                                           OverdraftFacilitiesChangeLimitRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "change-limit", body, OverdraftFacility.class,
                () -> overdrafts.changeLimit(id, Http.decimal(req, "limit_amount"), Http.text(req, "reason")));
    }
}
