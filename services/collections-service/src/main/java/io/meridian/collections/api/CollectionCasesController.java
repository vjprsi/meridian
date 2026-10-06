package io.meridian.collections.api;

import io.meridian.collections.api.generated.CollectionCasesApi;
import io.meridian.collections.api.generated.model.CollectionAction;
import io.meridian.collections.api.generated.model.CollectionActionPage;
import io.meridian.collections.api.generated.model.CollectionCase;
import io.meridian.collections.api.generated.model.CollectionCaseUpdate;
import io.meridian.collections.api.generated.model.CollectionCasesAssignRequest;
import io.meridian.collections.api.generated.model.CollectionCasesCloseRequest;
import io.meridian.collections.api.generated.model.CollectionCasesLogActionRequest;
import io.meridian.collections.api.generated.model.CollectionCasesMarkDisputeRequest;
import io.meridian.collections.api.generated.model.CollectionCasesOpenRequest;
import io.meridian.collections.api.generated.model.CollectionCasesPaymentPlanRequest;
import io.meridian.collections.api.generated.model.CollectionCasesPromiseRequest;
import io.meridian.collections.api.generated.model.CollectionCasesSendToAgencyRequest;
import io.meridian.collections.api.generated.model.CollectionCasesStartLegalRequest;
import io.meridian.collections.api.generated.model.LegalProceeding;
import io.meridian.collections.api.generated.model.NextBestAction;
import io.meridian.collections.api.generated.model.PaymentPlan;
import io.meridian.collections.api.generated.model.PromiseToPay;
import io.meridian.collections.application.ArrangementService;
import io.meridian.collections.application.CaseService;
import io.meridian.collections.application.CollectionsEod;
import io.meridian.collections.application.LegalService;
import io.meridian.collections.application.NextBestActionService;
import io.meridian.collections.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Collections cases: opening from delinquency, working the case, arrangements, legal, closure, EOD run. */
@RestController
public class CollectionCasesController implements CollectionCasesApi {
    private static final Set<String> PROTECTED = Set.of("customer_id", "exposure_type", "exposure_id", "currency");

    private final CaseService cases;
    private final ArrangementService arrangements;
    private final LegalService legal;
    private final NextBestActionService nba;
    private final CollectionsEod eod;
    private final Actions actions;
    private final CrudEngine crud;
    private final Rows rows;

    public CollectionCasesController(CaseService cases, ArrangementService arrangements, LegalService legal,
                                     NextBestActionService nba, CollectionsEod eod, Actions actions, CrudEngine crud,
                                     Rows rows) {
        this.cases = cases;
        this.arrangements = arrangements;
        this.legal = legal;
        this.nba = nba;
        this.eod = eod;
        this.actions = actions;
        this.crud = crud;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<CollectionCase> collectionCasesOpen(String idempotencyKey, CollectionCasesOpenRequest body) {
        JsonNode r = actions.tree(body);
        return actions.action(idempotencyKey, null, "open", body, CollectionCase.class, () -> cases.openOrRefresh(
                new CaseService.Signal(Http.uuid(r, "customer_id"), Http.text(r, "exposure_type"), Http.uuid(r, "exposure_id"),
                        Http.integer(r, "days_past_due"), Http.decimal(r, "overdue_amount"),
                        Http.decimal(r, "total_outstanding"), Http.text(r, "currency"), Http.uuid(r, "product_id"),
                        Http.text(r, "risk_grade"))));
    }

    @Override
    public ResponseEntity<CollectionCase> collectionCasesUpdate(UUID id, String ifMatch, CollectionCaseUpdate body) {
        TenantContext.require();
        ObjectNodeGuard.notTerminal(rows, CaseService.TABLE, id, Set.of("RESOLVED", "CLOSED"), "case_closed");
        return actions.guardedUpdate(crud, CaseService.TABLE, CollectionCase.class, id, ifMatch, body, PROTECTED,
                "the exposure of a case is fixed");
    }

    @Override
    public ResponseEntity<CollectionCase> collectionCasesAssign(UUID id, String idempotencyKey,
                                                               CollectionCasesAssignRequest body) {
        JsonNode r = actions.tree(body);
        return actions.action(idempotencyKey, id, "assign", body, CollectionCase.class,
                () -> cases.assign(id, Http.text(r, "assigned_to"), Http.uuid(r, "collection_queue_id")));
    }

    @Override
    public ResponseEntity<CollectionAction> collectionCasesLogAction(UUID id, String idempotencyKey,
                                                                    CollectionCasesLogActionRequest body) {
        JsonNode r = actions.tree(body);
        return actions.action(idempotencyKey, id, "log-action", body, CollectionAction.class,
                () -> cases.logAction(id, Http.text(r, "action_type"), Http.text(r, "outcome"), Http.text(r, "notes"),
                        Http.text(r, "contact_channel_address")));
    }

    @Override
    public ResponseEntity<PromiseToPay> collectionCasesPromise(UUID id, String idempotencyKey,
                                                              CollectionCasesPromiseRequest body) {
        JsonNode r = actions.tree(body);
        return actions.action(idempotencyKey, id, "promise", body, PromiseToPay.class,
                () -> arrangements.promise(id, Http.decimal(r, "promised_amount"), Http.date(r, "promised_date"),
                        Http.text(r, "channel")));
    }

    @Override
    public ResponseEntity<PaymentPlan> collectionCasesPaymentPlan(UUID id, String idempotencyKey,
                                                                 CollectionCasesPaymentPlanRequest body) {
        JsonNode r = actions.tree(body);
        return actions.action(idempotencyKey, id, "payment-plan", body, PaymentPlan.class,
                () -> arrangements.proposePlan(id, Http.integer(r, "installment_count"), Http.text(r, "frequency"),
                        Http.date(r, "start_date"), Http.bool(r, "interest_frozen"), Http.decimal(r, "total_amount")));
    }

    @Override
    public ResponseEntity<NextBestAction> collectionCasesNextBestAction(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "next-best-action", null, NextBestAction.class, () -> nba.suggest(id));
    }

    @Override
    public ResponseEntity<CollectionCase> collectionCasesSendToAgency(UUID id, String idempotencyKey,
                                                                     CollectionCasesSendToAgencyRequest body) {
        JsonNode r = actions.tree(body);
        return actions.action(idempotencyKey, id, "send-to-agency", body, CollectionCase.class,
                () -> cases.sendToAgency(id, Http.text(r, "agency_name")));
    }

    @Override
    public ResponseEntity<LegalProceeding> collectionCasesStartLegal(UUID id, String idempotencyKey,
                                                                    CollectionCasesStartLegalRequest body) {
        JsonNode r = actions.tree(body);
        return actions.action(idempotencyKey, id, "start-legal", body, LegalProceeding.class,
                () -> legal.start(id, Http.decimal(r, "claim_amount"), Http.text(r, "law_firm"), Http.text(r, "notes")));
    }

    @Override
    public ResponseEntity<CollectionCase> collectionCasesMarkDispute(UUID id, String idempotencyKey,
                                                                    CollectionCasesMarkDisputeRequest body) {
        JsonNode r = actions.tree(body);
        return actions.action(idempotencyKey, id, "mark-dispute", body, CollectionCase.class,
                () -> cases.markDispute(id, Boolean.TRUE.equals(Http.bool(r, "is_disputed")), Http.text(r, "notes")));
    }

    @Override
    public ResponseEntity<CollectionCase> collectionCasesClose(UUID id, String idempotencyKey,
                                                              CollectionCasesCloseRequest body) {
        JsonNode r = actions.tree(body);
        return actions.action(idempotencyKey, id, "close", body, CollectionCase.class,
                () -> cases.close(id, Http.text(r, "resolution"), Http.text(r, "notes")));
    }

    @Override
    public ResponseEntity<CollectionActionPage> collectionCasesActions(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        rows.get(CaseService.TABLE, id);
        return crud.list("collection_action", CollectionActionPage.class,
                Crud.params().with("collection_case_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<Map<String, Object>> collectionCasesEodStrategyRun(LocalDate businessDate, String idempotencyKey) {
        if (businessDate == null) {
            throw ApiException.badRequest("business_date_required", "business_date is required");
        }
        return actions.objectAction(idempotencyKey, "eod-strategy-run", Map.of("business_date", businessDate.toString()),
                () -> eod.run(businessDate));
    }
}
