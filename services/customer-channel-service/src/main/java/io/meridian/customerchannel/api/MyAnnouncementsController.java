package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyAnnouncementsApi;
import io.meridian.customerchannel.api.generated.model.AnnouncementPage;
import io.meridian.customerchannel.application.EngagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Announcements currently targeted at the customer. */
@RestController
public class MyAnnouncementsController implements MyAnnouncementsApi {
    private final EngagementService engagement;
    private final Http http;

    public MyAnnouncementsController(EngagementService engagement, Http http) {
        this.engagement = engagement;
        this.http = http;
    }

    @Override
    public ResponseEntity<AnnouncementPage> myAnnouncementsList(String app, String placement, String language,
                                                                Integer limit, String cursor) {
        return http.ok(engagement.announcements(app, placement, language, limit, cursor), AnnouncementPage.class);
    }
}
