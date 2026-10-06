package io.meridian.carddispute.api;

import io.meridian.carddispute.api.generated.CardDisputeEvidenceApi;
import io.meridian.carddispute.api.generated.model.CardDisputeEvidence;
import io.meridian.carddispute.api.generated.model.CardDisputeEvidenceCreate;
import io.meridian.carddispute.application.DisputeService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Dispute evidence: attach while the dispute is open; delete only before submission to the scheme. */
@RestController
public class CardDisputeEvidenceController implements CardDisputeEvidenceApi {
    private final DisputeService disputes;
    private final Actions actions;
    private final CrudEngine crud;

    public CardDisputeEvidenceController(DisputeService disputes, Actions actions, CrudEngine crud) {
        this.disputes = disputes;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<CardDisputeEvidence> cardDisputeEvidenceCreate(String idempotencyKey,
                                                                       CardDisputeEvidenceCreate body) {
        return actions.create(idempotencyKey, body, CardDisputeEvidence.class,
                () -> disputes.addEvidence(crud.toColumns("card_dispute_evidence", body)));
    }

    @Override
    public ResponseEntity<Void> cardDisputeEvidenceDelete(UUID id) {
        TenantContext.require();
        disputes.deleteEvidence(id);
        return ResponseEntity.noContent().build();
    }
}
