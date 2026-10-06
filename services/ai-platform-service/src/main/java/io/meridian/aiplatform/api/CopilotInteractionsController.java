package io.meridian.aiplatform.api;

import io.meridian.aiplatform.api.generated.CopilotInteractionsApi;
import io.meridian.aiplatform.api.generated.model.AiCopilotFeedback;
import io.meridian.aiplatform.api.generated.model.CopilotInteraction;
import io.meridian.aiplatform.api.generated.model.CopilotInteractionsApproveActionRequest;
import io.meridian.aiplatform.api.generated.model.CopilotInteractionsFeedbackRequest;
import io.meridian.aiplatform.api.generated.model.CopilotInteractionsRejectActionRequest;
import io.meridian.aiplatform.application.CopilotService;
import io.meridian.aiplatform.application.Fields;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Human decisions on proposed actions (new append-only audit records) and feedback. */
@RestController
public class CopilotInteractionsController implements CopilotInteractionsApi {
    private final CopilotService copilots;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public CopilotInteractionsController(CopilotService copilots, Idempotency idempotency, Responses responses, JsonMapper json) {
        this.copilots = copilots;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<CopilotInteraction> copilotInteractionsApproveAction(UUID id, String idempotencyKey,
                                                                              CopilotInteractionsApproveActionRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("approve-action", id, req), CopilotInteraction.class,
                () -> responses.created(copilots.decide(id, true, Fields.text(req, "comment")), CopilotInteraction.class));
    }

    @Override
    public ResponseEntity<CopilotInteraction> copilotInteractionsRejectAction(UUID id, String idempotencyKey,
                                                                             CopilotInteractionsRejectActionRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Fields.requireText(req, "reason");
        return idempotency.execute(idempotencyKey, responses.action("reject-action", id, req), CopilotInteraction.class,
                () -> responses.created(copilots.decide(id, false, reason), CopilotInteraction.class));
    }

    @Override
    public ResponseEntity<AiCopilotFeedback> copilotInteractionsFeedback(UUID id, String idempotencyKey,
                                                                       CopilotInteractionsFeedbackRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        Integer rating = Fields.integer(req, "rating");
        if (rating == null) {
            throw Fields.invalid("rating", "required", "rating is required");
        }
        return idempotency.execute(idempotencyKey, responses.action("feedback", id, req), AiCopilotFeedback.class,
                () -> responses.created(copilots.feedback(id, rating, Fields.text(req, "comment")), AiCopilotFeedback.class));
    }
}
