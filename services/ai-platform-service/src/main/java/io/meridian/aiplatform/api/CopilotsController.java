package io.meridian.aiplatform.api;

import io.meridian.aiplatform.api.generated.CopilotsApi;
import io.meridian.aiplatform.api.generated.model.CopilotDefinition;
import io.meridian.aiplatform.api.generated.model.CopilotDefinitionCreate;
import io.meridian.aiplatform.api.generated.model.CopilotDefinitionUpdate;
import io.meridian.aiplatform.api.generated.model.CopilotInteraction;
import io.meridian.aiplatform.api.generated.model.CopilotsActivateRequest;
import io.meridian.aiplatform.api.generated.model.CopilotsAskRequest;
import io.meridian.aiplatform.api.generated.model.CopilotsDisableRequest;
import io.meridian.aiplatform.application.CopilotService;
import io.meridian.aiplatform.application.Fields;
import io.meridian.aiplatform.infrastructure.Store;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Copilot definitions, activation under the gateway policy, and the audited ask flow. */
@RestController
public class CopilotsController implements CopilotsApi {
    private final CopilotService copilots;
    private final Idempotency idempotency;
    private final Responses responses;
    private final CrudEngine crud;
    private final Store store;
    private final JsonMapper json;

    public CopilotsController(CopilotService copilots, Idempotency idempotency, Responses responses, CrudEngine crud,
                              Store store, JsonMapper json) {
        this.copilots = copilots;
        this.idempotency = idempotency;
        this.responses = responses;
        this.crud = crud;
        this.store = store;
        this.json = json;
    }

    @Override
    public ResponseEntity<CopilotDefinition> copilotsCreate(String idempotencyKey, CopilotDefinitionCreate body) {
        TenantContext.require();
        ObjectNode values = crud.toColumns("copilot_definition", body);
        copilots.prepare(values, null);
        values.put("status", "DRAFT");
        return idempotency.execute(idempotencyKey, body, CopilotDefinition.class,
                () -> responses.created(crud.insert("copilot_definition", values), CopilotDefinition.class));
    }

    @Override
    public ResponseEntity<CopilotDefinition> copilotsUpdate(UUID id, String ifMatch, CopilotDefinitionUpdate body) {
        TenantContext.require();
        int version = Fields.ifMatch(ifMatch);
        JsonNode existing = store.require("copilot_definition", id);
        ObjectNode values = crud.toColumns("copilot_definition", body);
        if (values.isEmpty()) {
            return responses.ok(existing.toString(), CopilotDefinition.class);
        }
        copilots.prepare(values, existing);
        String row = crud.updateRow("copilot_definition", Map.of("id", id), values, version).orElseThrow(() ->
                ApiException.conflict("version_conflict", "If-Match " + version + " does not match the current version; reload and retry"));
        return responses.ok(row, CopilotDefinition.class);
    }

    @Override
    public ResponseEntity<CopilotDefinition> copilotsActivate(UUID id, String idempotencyKey, CopilotsActivateRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("activate", id, req), CopilotDefinition.class,
                () -> responses.ok(copilots.activate(id, Fields.text(req, "comment")), CopilotDefinition.class));
    }

    @Override
    public ResponseEntity<CopilotDefinition> copilotsDisable(UUID id, String idempotencyKey, CopilotsDisableRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Fields.requireText(req, "reason");
        return idempotency.execute(idempotencyKey, responses.action("disable", id, req), CopilotDefinition.class,
                () -> responses.ok(copilots.disable(id, reason), CopilotDefinition.class));
    }

    @Override
    public ResponseEntity<CopilotInteraction> copilotsAsk(UUID id, String idempotencyKey, CopilotsAskRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("ask", id, req), CopilotInteraction.class,
                () -> responses.created(copilots.ask(id, req), CopilotInteraction.class));
    }
}
