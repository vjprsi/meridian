package io.meridian.notification.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.notification.api.generated.NotificationsApi;
import io.meridian.notification.api.generated.model.Notification;
import io.meridian.notification.api.generated.model.NotificationCreate;
import io.meridian.notification.api.generated.model.NotificationDeliveryAttemptPage;
import io.meridian.notification.api.generated.model.NotificationsProviderCallbacksEventsRequest;
import io.meridian.notification.api.generated.model.NotificationsSendTemplatedRequest;
import io.meridian.notification.application.NotificationService;
import io.meridian.notification.application.ProviderCallbackService;
import io.meridian.notification.domain.CallbackSignature;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import jakarta.servlet.http.HttpServletRequest;
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
    private final ProviderCallbackService callbacks;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;
    private final HttpServletRequest request;

    public NotificationsController(NotificationService notifications, ProviderCallbackService callbacks, CrudEngine crud,
                                   Idempotency idempotency, JsonMapper json, HttpServletRequest request) {
        this.notifications = notifications;
        this.callbacks = callbacks;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
        this.request = request;
    }

    @Override
    public ResponseEntity<Notification> notificationsCreate(String idempotencyKey, NotificationCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Notification.class, () -> submitted(notifications.create(body)));
    }

    @Override
    public ResponseEntity<Notification> notificationsSendTemplated(String idempotencyKey,
                                                                   NotificationsSendTemplatedRequest body) {
        TenantContext.require();
        var send = new NotificationService.TemplatedSend(body.getTemplateCode(), body.getRecipientType().getValue(),
                body.getRecipientId(), body.getChannel() == null ? null : body.getChannel().getValue(),
                body.getVariables() == null ? Map.of() : body.getVariables(),
                body.getCategory() == null ? null : body.getCategory().getValue(), null, null, body.getLocale(),
                body.getDestination(), body.getPriority() == null ? null : body.getPriority().getValue(),
                body.getScheduledAt(), body.getExpiresAt(), body.getData());
        return idempotency.execute(idempotencyKey, body, Notification.class,
                () -> submitted(notifications.sendTemplated(send)));
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

    /** Public, signature-verified delivery receipt; the tenant comes from the registered callback URL. */
    @Override
    public ResponseEntity<Map<String, Object>> notificationsProviderCallbacksEvents(
            String provider, UUID tenantId, NotificationsProviderCallbacksEventsRequest body) {
        return ResponseEntity.ok(callbacks.handle(provider, tenantId, body.getPayload(),
                request.getHeader(CallbackSignature.HEADER)));
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
