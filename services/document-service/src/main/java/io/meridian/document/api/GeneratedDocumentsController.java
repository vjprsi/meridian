package io.meridian.document.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.document.api.generated.GeneratedDocumentsApi;
import io.meridian.document.api.generated.model.GeneratedDocument;
import io.meridian.document.api.generated.model.GeneratedDocumentCreate;
import io.meridian.document.application.GenerationService;
import io.meridian.document.config.RawBodyConfig;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generated documents. The request may carry a free-form {@code data} object with the template variables
 * (not in the generated model; read from the raw body).
 * Contract: generated/openapi/services/document-service.openapi.yaml
 */
@RestController
public class GeneratedDocumentsController implements GeneratedDocumentsApi {
    private final GenerationService generation;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public GeneratedDocumentsController(GenerationService generation, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.generation = generation;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<GeneratedDocument> generatedDocumentsCreate(String idempotencyKey, GeneratedDocumentCreate body) {
        TenantContext.require();
        byte[] raw = RawBodyConfig.currentBody();
        JsonNode data = raw.length == 0 ? null : json.readTree(raw).get("data");
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("body", body);
        fingerprint.put("data", data);
        return idempotency.execute(idempotencyKey, fingerprint, GeneratedDocument.class,
                () -> Responses.created(crud.read(generation.generate(body, data), GeneratedDocument.class)));
    }
}
