package io.meridian.document.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.document.api.generated.DocumentTemplatesApi;
import io.meridian.document.api.generated.model.ApprovalRequest;
import io.meridian.document.api.generated.model.DocumentTemplate;
import io.meridian.document.api.generated.model.DocumentTemplateCreate;
import io.meridian.document.api.generated.model.DocumentTemplateUpdate;
import io.meridian.document.api.generated.model.DocumentTemplatesPreviewRequest;
import io.meridian.document.application.TemplateService;
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
 * Document templates: versioned create, DRAFT-only edits, preview, publish (maker-checker), retire.
 * Contract: generated/openapi/services/document-service.openapi.yaml
 */
@RestController
public class DocumentTemplatesController implements DocumentTemplatesApi {
    private final TemplateService templates;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public DocumentTemplatesController(TemplateService templates, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.templates = templates;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<DocumentTemplate> documentTemplatesCreate(String idempotencyKey, DocumentTemplateCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, DocumentTemplate.class,
                () -> Responses.created(crud.read(templates.create(body), DocumentTemplate.class)));
    }

    @Override
    public ResponseEntity<DocumentTemplate> documentTemplatesUpdate(UUID id, String ifMatch, DocumentTemplateUpdate body) {
        TenantContext.require();
        templates.validateUpdate(id, body);
        return crud.update("document_template", DocumentTemplate.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> documentTemplatesPreview(UUID id, String idempotencyKey,
                                                                        DocumentTemplatesPreviewRequest body) {
        TenantContext.require();
        JsonNode data = json.valueToTree(body.getData());
        return idempotency.execute(idempotencyKey, Responses.op("preview", id, body), Responses.JSON_OBJECT,
                () -> ResponseEntity.ok(templates.preview(id, data)));
    }

    @Override
    public ResponseEntity<ApprovalRequest> documentTemplatesPublish(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("publish", id), ApprovalRequest.class,
                () -> ResponseEntity.ok(json.treeToValue(templates.publish(id, idempotencyKey), ApprovalRequest.class)));
    }

    @Override
    public ResponseEntity<DocumentTemplate> documentTemplatesRetire(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("retire", id), DocumentTemplate.class,
                () -> ResponseEntity.ok(crud.read(templates.retire(id), DocumentTemplate.class)));
    }
}
