package io.meridian.inbox.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.inbox.api.generated.AnnouncementsApi;
import io.meridian.inbox.api.generated.model.Announcement;
import io.meridian.inbox.api.generated.model.AnnouncementCreate;
import io.meridian.inbox.api.generated.model.AnnouncementPage;
import io.meridian.inbox.api.generated.model.AnnouncementUpdate;
import io.meridian.inbox.api.generated.model.AnnouncementsDismissRequest;
import io.meridian.inbox.api.generated.model.AnnouncementsWithdrawRequest;
import io.meridian.inbox.application.AnnouncementService;
import io.meridian.inbox.application.Json;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Announcements: DRAFT editing, publish / withdraw, scheduled status refresh, customer targeting and dismissal. */
@RestController
public class AnnouncementsController implements AnnouncementsApi {
    private final AnnouncementService announcements;
    private final Http http;
    private final Idempotency idempotency;
    private final Callers callers;

    public AnnouncementsController(AnnouncementService announcements, Http http, Idempotency idempotency, Callers callers) {
        this.callers = callers;
        this.announcements = announcements;
        this.http = http;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<Announcement> announcementsCreate(String idempotencyKey, AnnouncementCreate body) {
        return http.created(idempotencyKey, "announcements.create", body, Announcement.class, () -> announcements.create(body));
    }

    @Override
    public ResponseEntity<Announcement> announcementsUpdate(UUID id, String ifMatch, AnnouncementUpdate body) {
        TenantContext.require();
        return http.withEtag(announcements.update(id, body, Json.parseVersion(ifMatch)), Announcement.class);
    }

    @Override
    public ResponseEntity<Void> announcementsDelete(UUID id) {
        TenantContext.require();
        announcements.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Announcement> announcementsPublish(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "announcements.publish", id, null, Announcement.class, () -> announcements.publish(id));
    }

    @Override
    public ResponseEntity<Announcement> announcementsWithdraw(UUID id, String idempotencyKey, AnnouncementsWithdrawRequest body) {
        String reason = Json.text(http.tree(body), "reason");
        return http.ok(idempotencyKey, "announcements.withdraw", id, body, Announcement.class,
                () -> announcements.withdraw(id, reason));
    }

    @Override
    public ResponseEntity<Void> announcementsDismiss(UUID id, String idempotencyKey, AnnouncementsDismissRequest body) {
        TenantContext.require();
        UUID customer = callers.current().customerFor(UUID.fromString(Json.required(http.tree(body), "customer_id")));
        return idempotency.execute(idempotencyKey, Map.of("op", "announcements.dismiss", "id", id.toString(),
                "customer_id", customer.toString()), Void.class, () -> {
                    announcements.dismiss(id, customer);
                    return ResponseEntity.noContent().build();
                });
    }

    @Override
    public ResponseEntity<AnnouncementPage> announcementsActive(UUID customerId, String app, String placement, String language,
                                                                Integer limit, String cursor) {
        TenantContext.require();
        callers.current().customerFor(customerId);
        return ResponseEntity.ok(http.read(announcements.active(customerId, app, placement, language, limit),
                AnnouncementPage.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> announcementsRefreshStatus(String idempotencyKey) {
        return http.document(idempotencyKey, "announcements.refresh", null, null, announcements::refresh);
    }
}
