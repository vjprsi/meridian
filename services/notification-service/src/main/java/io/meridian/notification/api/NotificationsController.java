package io.meridian.notification.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.notification.api.generated.NotificationsApi;
import io.meridian.notification.api.generated.model.Notification;
import io.meridian.notification.api.generated.model.NotificationCreate;
import io.meridian.notification.api.generated.model.NotificationDeliveryAttemptPage;
import io.meridian.notification.api.generated.model.NotificationsProviderCallbacksEventsRequest;
import io.meridian.notification.api.generated.model.NotificationsSendTemplatedRequest;
import io.meridian.notification.application.NotificationService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Notifications: direct and templated sends, lifecycle actions, delivery attempts and provider receipts. */
@RestController
public class NotificationsController implements NotificationsApi {
    private final NotificationService notifications;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public NotificationsController(NotificationService notifications, CrudEngine crud, Idempotency idempotency,
                                   JsonMapper json) {
        this.notifications = notifications;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<Notification> notificationsCreate(String idempotencyKey, NotificationCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Notification.class, () -> submitted(notifications.create(body)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public ResponseEntity<Notification> notificationsSendTemplated(String idempotencyKey,
                                                                   NotificationsSendTemplatedRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        Map<String, Object> vars = req.hasNonNull("variables")
                ? json.convertValue(req.get("variables"), LinkedHashMap.class) : Map.of();
        return idempotency.execute(idempotencyKey, body, Notification.class, () -> submitted(notifications.sendTemplated(
                new NotificationService.TemplatedSend(req.get("template_code").asString(),
                        req.get("recipient_type").asString(), UUID.fromString(req.get("recipient_id").asString()),
                        req.hasNonNull("channel") ? req.get("channel").asString() : null, vars, null, null, null, null))));
    }

    @Override
    public ResponseEntity<Notification> notificationsResend(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("resend", id), Notification.class,
                () -> ok(notifications.resend(id)));
    }

    @Override
    public ResponseEntity<Notification> notificationsCancel(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("cancel", id), Notification.class,
                () -> ok(notifications.cancel(id)));
    }

    @Override
    public ResponseEntity<Notification> notificationsMarkRead(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.op("mark-read", id), Notification.class,
                () -> ok(notifications.markRead(id)));
    }

    @Override
    public ResponseEntity<NotificationDeliveryAttemptPage> notificationsAttempts(UUID id, Integer limit, String cursor) {
        crud.get("notification", Notification.class, Crud.params().with("id", id)); // 404 if not visible
        return crud.list("notification_delivery_attempt", NotificationDeliveryAttemptPage.class,
                Crud.params().with("notification_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ResponseEntity<Void> notificationsProviderCallbacksEvents(String provider, String idempotencyKey,
                                                                     NotificationsProviderCallbacksEventsRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        Map<String, Object> payload = json.convertValue(req.get("payload"), LinkedHashMap.class);
        ResponseEntity<Map> r = idempotency.execute(idempotencyKey, Map.of("provider", provider, "body", req), Map.class,
                () -> ResponseEntity.ok(notifications.providerCallback(provider, payload)));
        return (ResponseEntity) r;
    }

    private ResponseEntity<Notification> submitted(NotificationService.Submitted s) {
        JsonNode row = json.readTree(s.row());
        Notification n = crud.read(s.row(), Notification.class);
        return s.created() ? Responses.created(n, row) : ResponseEntity.status(HttpStatus.OK).body(n);
    }

    private ResponseEntity<Notification> ok(String row) {
        return ResponseEntity.ok(crud.read(row, Notification.class));
    }
}
