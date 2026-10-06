package io.meridian.document.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.document.api.generated.GeneratedDocumentsApi;
import io.meridian.document.api.generated.model.GeneratedDocument;
import io.meridian.document.api.generated.model.GeneratedDocumentCreate;
import io.meridian.document.application.GenerationService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generated documents: renders an ACTIVE template with the request's {@code data} (template variables).
 * Contract: generated/openapi/services/document-service.openapi.yaml
 */
@RestController
public class GeneratedDocumentsController implements GeneratedDocumentsApi {
    private final GenerationService generation;
    private final CrudEngine crud;
    private final Idempotency idempotency;

    public GeneratedDocumentsController(GenerationService generation, CrudEngine crud, Idempotency idempotency) {
        this.generation = generation;
        this.crud = crud;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<GeneratedDocument> generatedDocumentsCreate(String idempotencyKey, GeneratedDocumentCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, GeneratedDocument.class,
                () -> Responses.created(crud.read(generation.generate(body), GeneratedDocument.class)));
    }
}
