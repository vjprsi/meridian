package io.meridian.crm.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.api.generated.ServiceCasesApi;
import io.meridian.crm.api.generated.model.CaseComment;
import io.meridian.crm.api.generated.model.CaseCommentPage;
import io.meridian.crm.api.generated.model.Complaint;
import io.meridian.crm.api.generated.model.ServiceCase;
import io.meridian.crm.api.generated.model.ServiceCaseCreate;
import io.meridian.crm.api.generated.model.ServiceCaseUpdate;
import io.meridian.crm.api.generated.model.ServiceCasesAssignRequest;
import io.meridian.crm.api.generated.model.ServiceCasesCancelRequest;
import io.meridian.crm.api.generated.model.ServiceCasesCloseRequest;
import io.meridian.crm.api.generated.model.ServiceCasesCommentsRequest;
import io.meridian.crm.api.generated.model.ServiceCasesConvertToComplaintRequest;
import io.meridian.crm.api.generated.model.ServiceCasesCustomerRespondedRequest;
import io.meridian.crm.api.generated.model.ServiceCasesEscalateRequest;
import io.meridian.crm.api.generated.model.ServiceCasesPendingCustomerRequest;
import io.meridian.crm.api.generated.model.ServiceCasesReopenRequest;
import io.meridian.crm.api.generated.model.ServiceCasesResolveRequest;
import io.meridian.crm.application.CaseService;
import io.meridian.crm.application.Json;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Service cases: SLA-driven lifecycle, escalation, comments, complaint conversion and the SLA monitor. */
@RestController
public class ServiceCasesController implements ServiceCasesApi {
    private final CaseService cases;
    private final CrudEngine crud;
    private final Http http;

    public ServiceCasesController(CaseService cases, CrudEngine crud, Http http) {
        this.cases = cases;
        this.crud = crud;
        this.http = http;
    }

    @Override
    public ResponseEntity<ServiceCase> serviceCasesCreate(String idempotencyKey, ServiceCaseCreate body) {
        return http.created(idempotencyKey, "cases.create", body, ServiceCase.class, () -> cases.create(body));
    }

    @Override
    public ResponseEntity<ServiceCase> serviceCasesUpdate(UUID id, String ifMatch, ServiceCaseUpdate body) {
        TenantContext.require();
        return http.withEtag(cases.update(id, ifMatch, body), ServiceCase.class);
    }

    @Override
    public ResponseEntity<ServiceCase> serviceCasesAssign(UUID id, String idempotencyKey, ServiceCasesAssignRequest body) {
        UUID assignee = Json.uuid(http.tree(body), "assigned_to");
        return http.ok(idempotencyKey, "cases.assign", id, body, ServiceCase.class, () -> cases.assign(id, assignee));
    }

    @Override
    public ResponseEntity<ServiceCase> serviceCasesStart(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "cases.start", id, null, ServiceCase.class, () -> cases.start(id));
    }

    @Override
    public ResponseEntity<ServiceCase> serviceCasesEscalate(UUID id, String idempotencyKey, ServiceCasesEscalateRequest body) {
        JsonNode req = http.tree(body);
        return http.ok(idempotencyKey, "cases.escalate", id, body, ServiceCase.class,
                () -> cases.escalate(id, Json.uuid(req, "work_queue_id"), Json.required(req, "reason")));
    }

    @Override
    public ResponseEntity<ServiceCase> serviceCasesPendingCustomer(UUID id, String idempotencyKey,
                                                                   ServiceCasesPendingCustomerRequest body) {
        String message = Json.text(http.tree(body), "message");
        return http.ok(idempotencyKey, "cases.pending-customer", id, body, ServiceCase.class,
                () -> cases.pendingCustomer(id, message));
    }

    @Override
    public ResponseEntity<ServiceCase> serviceCasesCustomerResponded(UUID id, String idempotencyKey,
                                                                     ServiceCasesCustomerRespondedRequest body) {
        String message = Json.text(http.tree(body), "message");
        return http.ok(idempotencyKey, "cases.customer-responded", id, body, ServiceCase.class,
                () -> cases.customerResponded(id, message));
    }

    @Override
    public ResponseEntity<ServiceCase> serviceCasesResolve(UUID id, String idempotencyKey, ServiceCasesResolveRequest body) {
        JsonNode req = http.tree(body);
        return http.ok(idempotencyKey, "cases.resolve", id, body, ServiceCase.class,
                () -> cases.resolve(id, Json.required(req, "resolution_code"), Json.required(req, "resolution_summary")));
    }

    @Override
    public ResponseEntity<ServiceCase> serviceCasesClose(UUID id, String idempotencyKey, ServiceCasesCloseRequest body) {
        JsonNode req = http.tree(body);
        Integer csat = req.hasNonNull("csat_score") ? req.get("csat_score").asInt() : null;
        return http.ok(idempotencyKey, "cases.close", id, body, ServiceCase.class, () -> cases.close(id, csat));
    }

    @Override
    public ResponseEntity<ServiceCase> serviceCasesReopen(UUID id, String idempotencyKey, ServiceCasesReopenRequest body) {
        String reason = Json.required(http.tree(body), "reason");
        return http.ok(idempotencyKey, "cases.reopen", id, body, ServiceCase.class, () -> cases.reopen(id, reason));
    }

    @Override
    public ResponseEntity<ServiceCase> serviceCasesCancel(UUID id, String idempotencyKey, ServiceCasesCancelRequest body) {
        String reason = Json.required(http.tree(body), "reason");
        return http.ok(idempotencyKey, "cases.cancel", id, body, ServiceCase.class, () -> cases.cancel(id, reason));
    }

    @Override
    public ResponseEntity<Complaint> serviceCasesConvertToComplaint(UUID id, String idempotencyKey,
                                                                    ServiceCasesConvertToComplaintRequest body) {
        String category = Json.required(http.tree(body), "category");
        return http.ok(idempotencyKey, "cases.convert-to-complaint", id, body, Complaint.class,
                () -> cases.convertToComplaint(id, category));
    }

    @Override
    public ResponseEntity<CaseCommentPage> serviceCasesComments(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        crud.findOne("service_case", Map.of("id", id)).orElseThrow(() -> ApiException.notFound("service case", id));
        return crud.list("case_comment", CaseCommentPage.class,
                Crud.params().with("service_case_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<CaseComment> serviceCasesAddComments(UUID id, String idempotencyKey, ServiceCasesCommentsRequest body) {
        JsonNode req = http.tree(body);
        return http.ok(idempotencyKey, "cases.comment", id, body, CaseComment.class, () -> cases.addComment(id, req));
    }

    @Override
    public ResponseEntity<Map<String, Object>> serviceCasesSlaCheck(String idempotencyKey) {
        return http.document(idempotencyKey, "cases.sla-check", null, null, cases::slaCheck);
    }
}
