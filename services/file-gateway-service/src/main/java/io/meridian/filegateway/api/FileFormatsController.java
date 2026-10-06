package io.meridian.filegateway.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.filegateway.api.generated.FileFormatsApi;
import io.meridian.filegateway.api.generated.model.FileFormatDefinition;
import io.meridian.filegateway.api.generated.model.FileFormatDefinitionCreate;
import io.meridian.filegateway.api.generated.model.FileFormatDefinitionUpdate;
import io.meridian.filegateway.api.generated.model.FileFormatsValidateSampleRequest;
import io.meridian.filegateway.application.FormatService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** File format layouts: validated definitions and sample validation. */
@RestController
public class FileFormatsController implements FileFormatsApi {
    private static final String TABLE = "file_format_definition";

    private final FormatService formats;
    private final CrudEngine crud;
    private final Updates updates;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public FileFormatsController(FormatService formats, CrudEngine crud, Updates updates, Idempotency idempotency,
                                 JsonMapper json) {
        this.formats = formats;
        this.crud = crud;
        this.updates = updates;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<FileFormatDefinition> fileFormatsCreate(String idempotencyKey, FileFormatDefinitionCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, FileFormatDefinition.class, () -> {
            ObjectNode values = crud.toColumns(TABLE, body);
            formats.validateDefinition(values);
            return ResponseEntity.status(HttpStatus.CREATED).body(crud.read(crud.insert(TABLE, values), FileFormatDefinition.class));
        });
    }

    @Override
    public ResponseEntity<FileFormatDefinition> fileFormatsUpdate(UUID id, String ifMatch, FileFormatDefinitionUpdate body) {
        TenantContext.require();
        return updates.apply(TABLE, id, ifMatch, crud.toColumns(TABLE, body), FileFormatDefinition.class,
                (merged, changes) -> formats.validateDefinition(merged));
    }

    /** The sample is a {@code document_id} or inline {@code content} / {@code content_base64}. */
    @Override
    public ResponseEntity<Map<String, Object>> fileFormatsValidateSample(UUID id, String idempotencyKey,
                                                                         FileFormatsValidateSampleRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return Http.object(json, idempotency.execute(idempotencyKey, req, JsonNode.class,
                () -> ResponseEntity.ok(formats.validateSample(id, req))));
    }
}
