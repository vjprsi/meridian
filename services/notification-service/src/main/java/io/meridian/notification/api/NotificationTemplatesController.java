package io.meridian.notification.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.notification.api.generated.NotificationTemplatesApi;
import io.meridian.notification.api.generated.model.ApprovalRequest;
import io.meridian.notification.api.generated.model.NotificationTemplate;
import io.meridian.notification.api.generated.model.NotificationTemplateCreate;
import io.meridian.notification.api.generated.model.NotificationTemplateUpdate;
import io.meridian.notification.api.generated.model.NotificationTemplatesPreviewRequest;
import io.meridian.notification.api.generated.model.NotificationTemplatesTestSendRequest;
import io.meridian.notification.application.TemplateService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Notification templates: versioned drafts, preview, maker-checker publish and test sends. */
@RestController
public class NotificationTemplatesController implements NotificationTemplatesApi {
    private final TemplateService templates;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public NotificationTemplatesController(TemplateService templates, CrudEngine crud, Idempotency idempotency,
                                           JsonMapper json) {
        this.templates = templates;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<NotificationTemplate> notificationTemplatesCreate(String idempotencyKey,
                                                                           NotificationTemplateCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, NotificationTemplate.class, () -> {
            String row = templates.create(body);
            return Responses.created(crud.read(row, NotificationTemplate.class), json.readTree(row));
        });
    }

    @Override
    public ResponseEntity<NotificationTemplate> notificationTemplatesUpdate(UUID id, String ifMatch,
                                                                           NotificationTemplateUpdate body) {
        TenantContext.require();
        return crud.update("notification_template", NotificationTemplate.class, templates.prepareUpdate(id, body),
                ifMatch, Crud.params().with("id", id));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ResponseEntity<Void> notificationTemplatesPreview(UUID id, String idempotencyKey,
                                                             NotificationTemplatesPreviewRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        Map<String, Object> vars = json.convertValue(req.get("variables"), LinkedHashMap.class);
        // Preview has no side effects, so it is answered directly (no idempotency record needed).
        return Responses.body(templates.preview(id, vars));
    }

    @Override
    public ResponseEntity<ApprovalRequest> notificationTemplatesPublish(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("publish", id), ApprovalRequest.class,
                () -> ResponseEntity.ok(json.treeToValue(templates.publish(id, idempotencyKey), ApprovalRequest.class)));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ResponseEntity<Void> notificationTemplatesTestSend(UUID id, String idempotencyKey,
                                                              NotificationTemplatesTestSendRequest body) {
        TenantContext.require();
        String destination = json.valueToTree(body).get("destination").asString();
        ResponseEntity<Map> r = idempotency.execute(idempotencyKey, Map.of("op", "test-send", "id", id.toString(),
                "destination", destination), Map.class,
                () -> ResponseEntity.ok(json.readValue(templates.testSend(id, destination), Map.class)));
        return (ResponseEntity) r;
    }
}
