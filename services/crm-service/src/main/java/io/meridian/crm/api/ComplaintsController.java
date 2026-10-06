package io.meridian.crm.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.api.generated.ComplaintsApi;
import io.meridian.crm.api.generated.model.Complaint;
import io.meridian.crm.api.generated.model.ComplaintCreate;
import io.meridian.crm.api.generated.model.ComplaintRedressPage;
import io.meridian.crm.api.generated.model.ComplaintUpdate;
import io.meridian.crm.api.generated.model.ComplaintsAcknowledgeRequest;
import io.meridian.crm.api.generated.model.ComplaintsAssignRequest;
import io.meridian.crm.api.generated.model.ComplaintsEscalateRequest;
import io.meridian.crm.api.generated.model.ComplaintsFinalResponseRequest;
import io.meridian.crm.api.generated.model.ComplaintsProposeResolutionRequest;
import io.meridian.crm.api.generated.model.ComplaintsWithdrawRequest;
import io.meridian.crm.application.ComplaintService;
import io.meridian.crm.application.Json;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Complaints under regulatory timelines, deadline monitoring and the regulatory return. */
@RestController
public class ComplaintsController implements ComplaintsApi {
    private final ComplaintService complaints;
    private final CrudEngine crud;
    private final Http http;

    public ComplaintsController(ComplaintService complaints, CrudEngine crud, Http http) {
        this.complaints = complaints;
        this.crud = crud;
        this.http = http;
    }

    @Override
    public ResponseEntity<Complaint> complaintsCreate(String idempotencyKey, ComplaintCreate body) {
        return http.created(idempotencyKey, "complaints.create", body, Complaint.class, () -> complaints.create(body));
    }

    @Override
    public ResponseEntity<Complaint> complaintsUpdate(UUID id, String ifMatch, ComplaintUpdate body) {
        TenantContext.require();
        return http.withEtag(complaints.update(id, ifMatch, body), Complaint.class);
    }

    @Override
    public ResponseEntity<Complaint> complaintsAcknowledge(UUID id, String idempotencyKey, ComplaintsAcknowledgeRequest body) {
        String message = Json.text(http.tree(body), "message");
        return http.ok(idempotencyKey, "complaints.acknowledge", id, body, Complaint.class,
                () -> complaints.acknowledge(id, message));
    }

    @Override
    public ResponseEntity<Complaint> complaintsAssign(UUID id, String idempotencyKey, ComplaintsAssignRequest body) {
        UUID handler = Json.uuid(http.tree(body), "assigned_to");
        return http.ok(idempotencyKey, "complaints.assign", id, body, Complaint.class, () -> complaints.assign(id, handler));
    }

    @Override
    public ResponseEntity<Complaint> complaintsInvestigate(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "complaints.investigate", id, null, Complaint.class, () -> complaints.investigate(id));
    }

    @Override
    public ResponseEntity<Complaint> complaintsProposeResolution(UUID id, String idempotencyKey,
                                                                 ComplaintsProposeResolutionRequest body) {
        JsonNode req = http.tree(body);
        return http.ok(idempotencyKey, "complaints.propose-resolution", id, body, Complaint.class,
                () -> complaints.proposeResolution(id, Json.required(req, "outcome"), Json.required(req, "root_cause"),
                        Json.text(req, "root_cause_category"), Json.text(req, "resolution_summary")));
    }

    @Override
    public ResponseEntity<Complaint> complaintsFinalResponse(UUID id, String idempotencyKey,
                                                             ComplaintsFinalResponseRequest body) {
        UUID document = Json.uuid(http.tree(body), "final_response_document_id");
        return http.ok(idempotencyKey, "complaints.final-response", id, body, Complaint.class,
                () -> complaints.finalResponse(id, document));
    }

    @Override
    public ResponseEntity<Complaint> complaintsEscalate(UUID id, String idempotencyKey, ComplaintsEscalateRequest body) {
        JsonNode req = http.tree(body);
        return http.ok(idempotencyKey, "complaints.escalate", id, body, Complaint.class,
                () -> complaints.escalate(id, Json.required(req, "escalated_to"), Json.text(req, "ombudsman_reference")));
    }

    @Override
    public ResponseEntity<Complaint> complaintsWithdraw(UUID id, String idempotencyKey, ComplaintsWithdrawRequest body) {
        String reason = Json.required(http.tree(body), "reason");
        return http.ok(idempotencyKey, "complaints.withdraw", id, body, Complaint.class, () -> complaints.withdraw(id, reason));
    }

    @Override
    public ResponseEntity<Complaint> complaintsClose(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "complaints.close", id, null, Complaint.class, () -> complaints.close(id));
    }

    @Override
    public ResponseEntity<ComplaintRedressPage> complaintsRedress(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        crud.findOne("complaint", Map.of("id", id)).orElseThrow(() -> ApiException.notFound("complaint", id));
        return crud.list("complaint_redress", ComplaintRedressPage.class,
                Crud.params().with("complaint_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<Map<String, Object>> complaintsReportsRegulatory(LocalDate from, LocalDate to, String country) {
        TenantContext.require();
        return http.query(complaints.report(from, to, country));
    }

    @Override
    public ResponseEntity<Map<String, Object>> complaintsDeadlineCheck(String idempotencyKey) {
        return http.document(idempotencyKey, "complaints.deadline-check", null, null, complaints::deadlineCheck);
    }
}
