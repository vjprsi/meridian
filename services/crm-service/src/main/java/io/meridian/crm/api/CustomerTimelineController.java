package io.meridian.crm.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.api.generated.CustomerTimelineApi;
import io.meridian.crm.application.TimelineService;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Customer timeline (generic list) and its aggregated summary for the customer 360. */
@RestController
public class CustomerTimelineController implements CustomerTimelineApi {
    private final TimelineService timeline;
    private final Http http;

    public CustomerTimelineController(TimelineService timeline, Http http) {
        this.timeline = timeline;
        this.http = http;
    }

    @Override
    public ResponseEntity<Map<String, Object>> customerTimelineSummary(UUID customerId, OffsetDateTime from, OffsetDateTime to) {
        TenantContext.require();
        return http.query(timeline.summary(customerId, from, to));
    }
}
