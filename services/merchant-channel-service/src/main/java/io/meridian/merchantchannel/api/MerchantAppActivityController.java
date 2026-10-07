package io.meridian.merchantchannel.api;

import io.meridian.merchantchannel.api.generated.MerchantAppActivityApi;
import io.meridian.merchantchannel.api.generated.model.MerchantActionPage;
import io.meridian.merchantchannel.application.ActivityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The merchant's app activity (OWNER: every user; others: their own). */
@RestController
public class MerchantAppActivityController implements MerchantAppActivityApi {
    private final ActivityService activity;
    private final Http http;

    public MerchantAppActivityController(ActivityService activity, Http http) {
        this.activity = activity;
        this.http = http;
    }

    @Override
    public ResponseEntity<MerchantActionPage> merchantAppActivityList(Integer limit, String cursor) {
        return http.ok(activity.list(limit, cursor), MerchantActionPage.class);
    }
}
