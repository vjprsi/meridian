package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyNotificationPreferencesApi;
import io.meridian.customerchannel.api.generated.model.MyNotificationPreferencesReplaceRequest;
import io.meridian.customerchannel.api.generated.model.NotificationPreference;
import io.meridian.customerchannel.api.generated.model.NotificationPreferencePage;
import io.meridian.customerchannel.application.ProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer's notification opt-ins per category and channel. */
@RestController
public class MyNotificationPreferencesController implements MyNotificationPreferencesApi {
    private final ProfileService profile;
    private final Http http;

    public MyNotificationPreferencesController(ProfileService profile, Http http) {
        this.profile = profile;
        this.http = http;
    }

    @Override
    public ResponseEntity<NotificationPreferencePage> myNotificationPreferencesList(Integer limit, String cursor) {
        return http.ok(profile.preferences(limit, cursor), NotificationPreferencePage.class);
    }

    @Override
    public ResponseEntity<NotificationPreference> myNotificationPreferencesReplace(
            String idempotencyKey, MyNotificationPreferencesReplaceRequest body) {
        return http.ok(profile.setPreference(http.body(body), idempotencyKey), NotificationPreference.class);
    }
}
