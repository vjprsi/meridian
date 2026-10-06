package io.meridian.carddispute.api;

import static io.meridian.carddispute.api.Actions.bool;
import static io.meridian.carddispute.api.Actions.decimal;
import static io.meridian.carddispute.api.Actions.text;

import io.meridian.carddispute.api.generated.CardDisputesApi;
import io.meridian.carddispute.api.generated.model.CardDispute;
import io.meridian.carddispute.api.generated.model.CardDisputeCreate;
import io.meridian.carddispute.api.generated.model.CardDisputeEventPage;
import io.meridian.carddispute.api.generated.model.CardDisputeUpdate;
import io.meridian.carddispute.api.generated.model.CardDisputesAcceptRepresentmentRequest;
import io.meridian.carddispute.api.generated.model.CardDisputesAssignRequest;
import io.meridian.carddispute.api.generated.model.CardDisputesCloseRequest;
import io.meridian.carddispute.api.generated.model.CardDisputesEscalateRequest;
import io.meridian.carddispute.api.generated.model.CardDisputesProvisionalCreditRequest;
import io.meridian.carddispute.api.generated.model.CardDisputesRecordRepresentmentRequest;
import io.meridian.carddispute.api.generated.model.CardDisputesRequestRetrievalRequest;
import io.meridian.carddispute.api.generated.model.CardDisputesReverseProvisionalCreditRequest;
import io.meridian.carddispute.api.generated.model.CardDisputesSubmitChargebackRequest;
import io.meridian.carddispute.application.DisputeService;
import io.meridian.carddispute.domain.DisputeStateMachine.Outcome;
import io.meridian.carddispute.domain.DisputeStateMachine.Stage;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Card disputes: intake, provisional credit, scheme stages, outcomes, timeline and copilot summary. */
@RestController
public class CardDisputesController implements CardDisputesApi {
    private final DisputeService disputes;
    private final Actions actions;
    private final CrudEngine crud;

    public CardDisputesController(DisputeService disputes, Actions actions, CrudEngine crud) {
        this.disputes = disputes;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<CardDispute> cardDisputesCreate(String idempotencyKey, CardDisputeCreate body) {
        return actions.create(idempotencyKey, body, CardDispute.class,
                () -> disputes.open(crud.toColumns("card_dispute", body)));
    }

    @Override
    public ResponseEntity<CardDispute> cardDisputesUpdate(UUID id, String ifMatch, CardDisputeUpdate body) {
        TenantContext.require();
        JsonNode row = disputes.update(id, ifMatch, crud.toColumns("card_dispute", body));
        return ResponseEntity.ok().eTag("\"" + row.get("row_version").asString() + "\"")
                .body(crud.read(row.toString(), CardDispute.class));
    }

    @Override
    public ResponseEntity<CardDispute> cardDisputesProvisionalCredit(UUID id, String idempotencyKey,
                                                                    CardDisputesProvisionalCreditRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "provisional-credit", body, CardDispute.class,
                () -> disputes.provisionalCredit(id, decimal(req, "amount")));
    }

    @Override
    public ResponseEntity<CardDispute> cardDisputesReverseProvisionalCredit(UUID id, String idempotencyKey,
                                                                           CardDisputesReverseProvisionalCreditRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "reverse-provisional-credit", body, CardDispute.class,
                () -> disputes.reverseProvisionalCredit(id, text(req, "reason")));
    }

    @Override
    public ResponseEntity<CardDispute> cardDisputesRequestRetrieval(UUID id, String idempotencyKey,
                                                                   CardDisputesRequestRetrievalRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "request-retrieval", body, CardDispute.class,
                () -> disputes.requestRetrieval(id, text(req, "note")));
    }

    @Override
    public ResponseEntity<CardDispute> cardDisputesSubmitChargeback(UUID id, String idempotencyKey,
                                                                   CardDisputesSubmitChargebackRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "submit-chargeback", body, CardDispute.class,
                () -> disputes.submitChargeback(id, text(req, "reason_code"), decimal(req, "amount")));
    }

    @Override
    public ResponseEntity<CardDispute> cardDisputesRecordRepresentment(UUID id, String idempotencyKey,
                                                                      CardDisputesRecordRepresentmentRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "record-representment", body, CardDispute.class,
                () -> disputes.recordRepresentment(id, decimal(req, "amount"), text(req, "note"),
                        text(req, "scheme_case_id")));
    }

    @Override
    public ResponseEntity<CardDispute> cardDisputesAcceptRepresentment(UUID id, String idempotencyKey,
                                                                      CardDisputesAcceptRepresentmentRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "accept-representment", body, CardDispute.class,
                () -> disputes.acceptRepresentment(id, text(req, "note"), bool(req, "customer_liable")));
    }

    @Override
    public ResponseEntity<CardDispute> cardDisputesEscalate(UUID id, String idempotencyKey,
                                                           CardDisputesEscalateRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "escalate", body, CardDispute.class,
                () -> disputes.escalate(id, Stage.valueOf(text(req, "stage")), text(req, "note")));
    }

    @Override
    public ResponseEntity<CardDispute> cardDisputesClose(UUID id, String idempotencyKey, CardDisputesCloseRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "close", body, CardDispute.class,
                () -> disputes.close(id, Outcome.valueOf(text(req, "outcome")), decimal(req, "recovered_amount"),
                        bool(req, "customer_liable"), text(req, "note")));
    }

    @Override
    public ResponseEntity<CardDispute> cardDisputesAssign(UUID id, String idempotencyKey, CardDisputesAssignRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "assign", body, CardDispute.class,
                () -> disputes.assign(id, text(req, "assigned_to")));
    }

    @Override
    public ResponseEntity<CardDispute> cardDisputesAiSummary(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "ai-summary", null, CardDispute.class, () -> disputes.aiSummary(id));
    }

    @Override
    public ResponseEntity<CardDisputeEventPage> cardDisputesEvents(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        if (crud.findOne("card_dispute", Map.of("id", id)).isEmpty()) {
            throw ApiException.notFound("card dispute", id);
        }
        return crud.list("card_dispute_event", CardDisputeEventPage.class,
                Crud.params().with("card_dispute_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<Map<String, Object>> cardDisputesDeadlineScan(String idempotencyKey, LocalDate asOf) {
        return actions.objectAction(idempotencyKey, "deadline-scan", asOf, () -> disputes.deadlineScan(asOf));
    }
}
