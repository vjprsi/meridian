package io.meridian.document.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.document.api.generated.DocumentsApi;
import io.meridian.document.api.generated.model.Document;
import io.meridian.document.api.generated.model.DocumentCreate;
import io.meridian.document.api.generated.model.DocumentUpdate;
import io.meridian.document.api.generated.model.DocumentsCompleteUploadRequest;
import io.meridian.document.api.generated.model.DocumentsLegalHoldRequest;
import io.meridian.document.application.DocumentService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Documents: registration, presigned content transfer, upload completion, legal hold, archive, retention-aware delete.
 * Contract: generated/openapi/services/document-service.openapi.yaml
 */
@RestController
public class DocumentsController implements DocumentsApi {
    private final DocumentService documents;
    private final CrudEngine crud;
    private final Idempotency idempotency;

    public DocumentsController(DocumentService documents, CrudEngine crud, Idempotency idempotency) {
        this.documents = documents;
        this.crud = crud;
        this.idempotency = idempotency;
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
    public ResponseEntity<Map<String, Object>> documentsUploadUrl(UUID id, String idempotencyKey) {
        TenantContext.require();
        // A presigned URL is a fresh credential each time: replays return the URL issued first while still valid.
        return idempotency.execute(idempotencyKey, Responses.op("upload-url", id), Responses.JSON_OBJECT,
                () -> ResponseEntity.ok(documents.uploadUrl(id)));
    }

    @Override
    public ResponseEntity<Document> documentsCompleteUpload(UUID id, String idempotencyKey,
                                                            DocumentsCompleteUploadRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("complete-upload", id, body), Document.class,
                () -> ResponseEntity.ok(crud.read(documents.completeUpload(id, body.getSizeBytes(), body.getChecksum(), null),
                        Document.class)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> documentsDownloadUrl(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok(documents.downloadUrl(id));
    }

    @Override
    public ResponseEntity<Document> documentsLegalHold(UUID id, String idempotencyKey, DocumentsLegalHoldRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("legal-hold", id, body), Document.class,
                () -> ResponseEntity.ok(crud.read(documents.legalHold(id, body.getLegalHold(), body.getReason()),
                        Document.class)));
    }

    @Override
    public ResponseEntity<Document> documentsArchive(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("archive", id), Document.class,
                () -> ResponseEntity.ok(crud.read(documents.archive(id), Document.class)));
    }
}
