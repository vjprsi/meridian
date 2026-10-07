package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyNotificationsApi;
import io.meridian.customerchannel.api.generated.model.Notification;
import io.meridian.customerchannel.api.generated.model.NotificationPage;
import io.meridian.customerchannel.application.EngagementService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Notifications sent to the customer. */
@RestController
public class MyNotificationsController implements MyNotificationsApi {
    private final EngagementService engagement;
    private final Http http;

    public MyNotificationsController(EngagementService engagement, Http http) {
        this.engagement = engagement;
        this.http = http;
    }

    @Override
    public ResponseEntity<NotificationPage> myNotificationsList(String channel, Integer limit, String cursor) {
        return http.ok(engagement.notifications(channel, limit, cursor), NotificationPage.class);
    }

    @Override
    public ResponseEntity<Notification> myNotificationsMarkRead(UUID notificationId, String idempotencyKey) {
        return http.ok(engagement.markRead(notificationId, idempotencyKey), Notification.class);
    }
}
