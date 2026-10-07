package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessActivityApi;
import io.meridian.businesschannel.api.generated.model.BusinessActionPage;
import io.meridian.businesschannel.application.ActivityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The business's portal activity (ADMIN: every user; others: their own). */
@RestController
public class BusinessActivityController implements BusinessActivityApi {
    private final ActivityService activity;
    private final Http http;

    public BusinessActivityController(ActivityService activity, Http http) {
        this.activity = activity;
        this.http = http;
    }

    @Override
    public ResponseEntity<BusinessActionPage> businessActivityList(Integer limit, String cursor) {
        return http.ok(activity.list(limit, cursor), BusinessActionPage.class);
    }
}
