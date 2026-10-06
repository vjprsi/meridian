package io.meridian.collections.api;

import io.meridian.collections.api.generated.LegalProceedingsApi;
import io.meridian.collections.api.generated.model.LegalProceeding;
import io.meridian.collections.api.generated.model.LegalProceedingUpdate;
import io.meridian.collections.api.generated.model.LegalProceedingsAdvanceRequest;
import io.meridian.collections.api.generated.model.LegalProceedingsApproveRequest;
import io.meridian.collections.api.generated.model.LegalProceedingsRejectRequest;
import io.meridian.collections.application.LegalService;
import io.meridian.collections.infrastructure.Rows;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Legal proceedings: four-eyes approval to litigate and the court stage state machine. */
@RestController
public class LegalProceedingsController implements LegalProceedingsApi {
    private static final Set<String> PROTECTED = Set.of("collection_case_id", "currency", "claim_amount");

    private final LegalService legal;
    private final Actions actions;
    private final CrudEngine crud;
    private final Rows rows;

    public LegalProceedingsController(LegalService legal, Actions actions, CrudEngine crud, Rows rows) {
        this.legal = legal;
        this.actions = actions;
        this.crud = crud;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<LegalProceeding> legalProceedingsUpdate(UUID id, String ifMatch, LegalProceedingUpdate body) {
        TenantContext.require();
        ObjectNodeGuard.notTerminal(rows, "legal_proceeding", id, Set.of("CLOSED", "REJECTED"), "legal_proceeding_closed");
        return actions.guardedUpdate(crud, "legal_proceeding", LegalProceeding.class, id, ifMatch, body, PROTECTED,
                "the claim is fixed at approval");
    }

    @Override
    public ResponseEntity<LegalProceeding> legalProceedingsApprove(UUID id, String idempotencyKey,
                                                                  LegalProceedingsApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, LegalProceeding.class,
                () -> legal.approve(id, Http.text(actions.tree(body), "comment")));
    }

    @Override
    public ResponseEntity<LegalProceeding> legalProceedingsReject(UUID id, String idempotencyKey,
                                                                 LegalProceedingsRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, LegalProceeding.class,
                () -> legal.reject(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<LegalProceeding> legalProceedingsAdvance(UUID id, String idempotencyKey,
                                                                  LegalProceedingsAdvanceRequest body) {
        return actions.action(idempotencyKey, id, "advance", body, LegalProceeding.class,
                () -> legal.advance(id, actions.tree(body)));
    }
}
