package io.meridian.document.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.document.api.generated.DocumentsApi;
import io.meridian.document.api.generated.model.Document;
import io.meridian.document.api.generated.model.DocumentCreate;
import io.meridian.document.api.generated.model.DocumentUpdate;
import io.meridian.document.api.generated.model.DocumentsLegalHoldRequest;
import io.meridian.document.application.DocumentService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Documents: registration, presigned content transfer, legal hold, archive, retention-aware delete.
 * Contract: generated/openapi/services/document-service.openapi.yaml
 */
@RestController
public class DocumentsController implements DocumentsApi {
    private final DocumentService documents;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public DocumentsController(DocumentService documents, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.documents = documents;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<Document> documentsCreate(String idempotencyKey, DocumentCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Document.class,
                () -> Responses.created(crud.read(documents.create(body), Document.class)));
    }

    @Override
    public ResponseEntity<Document> documentsUpdate(UUID id, String ifMatch, DocumentUpdate body) {
        TenantContext.require();
        documents.validateUpdate(id, body);
        return crud.update("document", Document.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Void> documentsDelete(UUID id) {
        TenantContext.require();
        documents.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> documentsUploadUrl(UUID id, String idempotencyKey) {
        TenantContext.require();
        // A presigned URL is a fresh credential each time: replays return the URL issued first while still valid.
        return Responses.erase(idempotency.execute(idempotencyKey, Responses.op("upload-url", id), JsonNode.class,
                () -> ResponseEntity.ok(json.valueToTree(documents.uploadUrl(id)))));
    }

    @Override
    public ResponseEntity<Void> documentsDownloadUrl(UUID id) {
        TenantContext.require();
        return Responses.body(documents.downloadUrl(id));
    }

    @Override
    public ResponseEntity<Document> documentsLegalHold(UUID id, String idempotencyKey, DocumentsLegalHoldRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, Responses.op("legal-hold", id, body), Document.class,
                () -> ResponseEntity.ok(crud.read(documents.legalHold(id, req.get("legal_hold").asBoolean(),
                        req.path("reason").asString(null)), Document.class)));
    }

    @Override
    public ResponseEntity<Document> documentsArchive(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("archive", id), Document.class,
                () -> ResponseEntity.ok(crud.read(documents.archive(id), Document.class)));
    }
}
