package io.meridian.crm.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.api.generated.CopilotSessionsApi;
import io.meridian.crm.api.generated.model.CopilotFeedback;
import io.meridian.crm.api.generated.model.CopilotFeedbackPage;
import io.meridian.crm.api.generated.model.CopilotSession;
import io.meridian.crm.api.generated.model.CopilotSessionCreate;
import io.meridian.crm.api.generated.model.CopilotSessionsFeedbackRequest;
import io.meridian.crm.application.CopilotService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Copilot sessions (grounded, audited generation) and staff feedback on their outputs. */
@RestController
public class CopilotSessionsController implements CopilotSessionsApi {
    private final CopilotService copilot;
    private final CrudEngine crud;
    private final Http http;

    public CopilotSessionsController(CopilotService copilot, CrudEngine crud, Http http) {
        this.copilot = copilot;
        this.crud = crud;
        this.http = http;
    }

    @Override
    public ResponseEntity<CopilotSession> copilotSessionsCreate(String idempotencyKey, CopilotSessionCreate body) {
        return http.created(idempotencyKey, "copilot.create", body, CopilotSession.class, () -> copilot.create(body));
    }

    @Override
    public ResponseEntity<CopilotFeedback> copilotSessionsFeedback(UUID id, String idempotencyKey,
                                                                   CopilotSessionsFeedbackRequest body) {
        JsonNode req = http.tree(body);
        return http.ok(idempotencyKey, "copilot.feedback", id, body, CopilotFeedback.class, () -> copilot.feedback(id, req));
    }

    @Override
    public ResponseEntity<CopilotFeedbackPage> copilotSessionsGetFeedback(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        crud.findOne("copilot_session", Map.of("id", id)).orElseThrow(() -> ApiException.notFound("copilot session", id));
        return crud.list("copilot_feedback", CopilotFeedbackPage.class,
                Crud.params().with("copilot_session_id", id).with("limit", limit).with("cursor", cursor));
    }
}
