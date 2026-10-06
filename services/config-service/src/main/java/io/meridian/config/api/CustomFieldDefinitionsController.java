package io.meridian.config.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.config.api.generated.CustomFieldDefinitionsApi;
import io.meridian.config.api.generated.model.CustomFieldDefinition;
import io.meridian.config.api.generated.model.CustomFieldDefinitionCreate;
import io.meridian.config.api.generated.model.CustomFieldDefinitionUpdate;
import io.meridian.config.api.generated.model.CustomFieldDefinitionsValidateRequest;
import io.meridian.config.application.CustomFieldService;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Custom field definitions (validated, evented) and validation of custom field values. */
@RestController
public class CustomFieldDefinitionsController implements CustomFieldDefinitionsApi {
    private final CustomFieldService fields;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public CustomFieldDefinitionsController(CustomFieldService fields, Idempotency idempotency, JsonMapper json) {
        this.fields = fields;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<CustomFieldDefinition> customFieldDefinitionsCreate(String idempotencyKey,
                                                                              CustomFieldDefinitionCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, CustomFieldDefinition.class,
                () -> Responses.row(json, HttpStatus.CREATED, fields.create(body), CustomFieldDefinition.class));
    }

    @Override
    public ResponseEntity<CustomFieldDefinition> customFieldDefinitionsUpdate(UUID id, String ifMatch,
                                                                              CustomFieldDefinitionUpdate body) {
        TenantContext.require();
        return Responses.row(json, HttpStatus.OK, fields.update(id, body, ifMatch), CustomFieldDefinition.class);
    }

    @Override
    public ResponseEntity<Void> customFieldDefinitionsDelete(UUID id) {
        TenantContext.require();
        fields.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Map<String, Object>> customFieldDefinitionsValidate(String idempotencyKey,
                                                                              CustomFieldDefinitionsValidateRequest body) {
        TenantContext.require();
        JsonNode values = body.getValues() == null ? null : json.valueToTree(body.getValues());
        return Responses.object(json, idempotency.execute(idempotencyKey, body, JsonNode.class,
                () -> ResponseEntity.ok(fields.validateValues(body.getTableName(), values))));
    }
}
