package io.meridian.casemanagement.api;

import io.meridian.casemanagement.api.generated.AmlCasesApi;
import io.meridian.casemanagement.api.generated.model.AmlCase;
import io.meridian.casemanagement.api.generated.model.AmlCaseAlert;
import io.meridian.casemanagement.api.generated.model.AmlCaseAlertPage;
import io.meridian.casemanagement.api.generated.model.AmlCaseCreate;
import io.meridian.casemanagement.api.generated.model.AmlCaseDisposition;
import io.meridian.casemanagement.api.generated.model.AmlCaseNote;
import io.meridian.casemanagement.api.generated.model.AmlCaseNotePage;
import io.meridian.casemanagement.api.generated.model.AmlCaseUpdate;
import io.meridian.casemanagement.api.generated.model.AmlCasesAlertsRequest;
import io.meridian.casemanagement.api.generated.model.AmlCasesAssignRequest;
import io.meridian.casemanagement.api.generated.model.AmlCasesCloseRequest;
import io.meridian.casemanagement.api.generated.model.AmlCasesEscalateRequest;
import io.meridian.casemanagement.api.generated.model.AmlCasesNotesRequest;
import io.meridian.casemanagement.api.generated.model.AmlCasesReopenRequest;
import io.meridian.casemanagement.application.CaseService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** AML / fraud / sanctions investigation cases. */
@RestController
public class AmlCasesController implements AmlCasesApi {
    private final CaseService cases;
    private final CrudEngine crud;
    private final Http http;

    public AmlCasesController(CaseService cases, CrudEngine crud, Http http) {
        this.cases = cases;
        this.crud = crud;
        this.http = http;
    }

    @Override
    public ResponseEntity<AmlCase> amlCasesCreate(String idempotencyKey, AmlCaseCreate body) {
        return http.once(idempotencyKey, body, AmlCase.class,
                () -> http.respond(HttpStatus.CREATED, cases.open(http.tree(body)), AmlCase.class));
    }

    @Override
    public ResponseEntity<AmlCase> amlCasesUpdate(UUID id, String ifMatch, AmlCaseUpdate body) {
        TenantContext.require();
        return http.ok(cases.update(id, http.tree(body), Http.ifMatch(ifMatch)), AmlCase.class);
    }

    @Override
    public ResponseEntity<AmlCase> amlCasesAssign(UUID id, String idempotencyKey, AmlCasesAssignRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, AmlCase.class,
                () -> http.ok(cases.assign(id, Http.text(req, "assigned_to"), Http.text(req, "team")), AmlCase.class));
    }

    @Override
    public ResponseEntity<AmlCase> amlCasesEscalate(UUID id, String idempotencyKey, AmlCasesEscalateRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, AmlCase.class,
                () -> http.ok(cases.escalate(id, Http.text(req, "escalated_to"), Http.text(req, "reason")), AmlCase.class));
    }

    @Override
    public ResponseEntity<AmlCaseDisposition> amlCasesClose(UUID id, String idempotencyKey, AmlCasesCloseRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, AmlCaseDisposition.class, () -> http.ok(cases.proposeDisposition(id,
                Http.text(req, "disposition"), Http.text(req, "rationale"), req.get("actions_taken")), AmlCaseDisposition.class));
    }

    @Override
    public ResponseEntity<AmlCase> amlCasesReopen(UUID id, String idempotencyKey, AmlCasesReopenRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, AmlCase.class, () -> http.ok(cases.reopen(id, Http.text(req, "reason")), AmlCase.class));
    }

    @Override
    public ResponseEntity<AmlCaseAlertPage> amlCasesAlerts(UUID id, Integer limit, String cursor) {
        crud.get("aml_case", Object.class, Crud.params().with("id", id));
        return crud.list("aml_case_alert", AmlCaseAlertPage.class,
                Crud.params().with("aml_case_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<AmlCaseAlert> amlCasesAddAlerts(UUID id, String idempotencyKey, AmlCasesAlertsRequest body) {
        return http.once(idempotencyKey, body, AmlCaseAlert.class,
                () -> http.ok(cases.linkAlert(id, http.tree(body)), AmlCaseAlert.class));
    }

    @Override
    public ResponseEntity<AmlCaseNotePage> amlCasesNotes(UUID id, Integer limit, String cursor) {
        crud.get("aml_case", Object.class, Crud.params().with("id", id));
        return crud.list("aml_case_note", AmlCaseNotePage.class,
                Crud.params().with("aml_case_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<AmlCaseNote> amlCasesAddNotes(UUID id, String idempotencyKey, AmlCasesNotesRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, AmlCaseNote.class, () -> http.ok(cases.addNote(id, Http.text(req, "body"),
                Http.text(req, "visibility"), req.get("attachments")), AmlCaseNote.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> amlCasesTimeline(UUID id, Boolean includeRestricted) {
        TenantContext.require();
        return ResponseEntity.ok(cases.timeline(id, Boolean.TRUE.equals(includeRestricted)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> amlCasesAiSummary(UUID id, String idempotencyKey) {
        TenantContext.require();
        return ResponseEntity.ok(cases.summarise(id));
    }
}
