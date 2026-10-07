package io.meridian.gl.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.gl.api.generated.GlJournalsApi;
import io.meridian.gl.api.generated.model.GlJournal;
import io.meridian.gl.api.generated.model.GlJournalApproval;
import io.meridian.gl.api.generated.model.GlJournalCreate;
import io.meridian.gl.api.generated.model.GlJournalLinePage;
import io.meridian.gl.api.generated.model.GlJournalRejection;
import io.meridian.gl.api.generated.model.GlJournalsApproveRequest;
import io.meridian.gl.api.generated.model.GlJournalsRejectRequest;
import io.meridian.gl.application.Fields;
import io.meridian.gl.application.JournalService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GL journals. Manual journals carry their debit/credit {@code lines}; they post to the GL only when approved by a
 * different principal, which records an append-only gl_journal_approval.
 */
@RestController
public class GlJournalsController implements GlJournalsApi {
    private final JournalService journals;
    private final Idempotency idempotency;
    private final Responses responses;
    private final CrudEngine crud;
    private final JsonMapper json;

    public GlJournalsController(JournalService journals, Idempotency idempotency, Responses responses, CrudEngine crud,
                                JsonMapper json) {
        this.journals = journals;
        this.idempotency = idempotency;
        this.responses = responses;
        this.crud = crud;
        this.json = json;
    }

    @Override
    public ResponseEntity<GlJournal> glJournalsCreate(String idempotencyKey, GlJournalCreate body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, body, GlJournal.class,
                () -> responses.created(journals.createManual(req), GlJournal.class));
    }

    @Override
    public ResponseEntity<GlJournal> glJournalsGet(UUID id) {
        TenantContext.require();
        return responses.ok(journals.withLines(id), GlJournal.class);
    }

    @Override
    public ResponseEntity<GlJournalApproval> glJournalsApprove(UUID id, String idempotencyKey,
                                                              GlJournalsApproveRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("approve", id, req), GlJournalApproval.class,
                () -> responses.ok(journals.approve(id, Fields.text(req, "comment")), GlJournalApproval.class));
    }

    @Override
    public ResponseEntity<GlJournalRejection> glJournalsReject(UUID id, String idempotencyKey, GlJournalsRejectRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("reject", id, req), GlJournalRejection.class,
                () -> responses.ok(journals.reject(id, Fields.text(req, "reason")), GlJournalRejection.class));
    }

    @Override
    public ResponseEntity<GlJournalLinePage> glJournalsLines(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        if (crud.findOne("gl_journal", Map.of("id", id)).isEmpty()) {
            throw ApiException.notFound("gl journal", id);
        }
        return crud.list("gl_journal_line", GlJournalLinePage.class,
                Crud.params().with("gl_journal_id", id).with("limit", limit).with("cursor", cursor));
    }
}
