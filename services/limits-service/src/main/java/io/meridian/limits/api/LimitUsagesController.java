package io.meridian.limits.api;

import io.meridian.limits.api.generated.LimitUsagesApi;
import io.meridian.limits.api.generated.model.LimitUsage;
import io.meridian.limits.api.generated.model.LimitUsagesResetRequest;
import io.meridian.limits.application.LimitAdminService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Usage counters (read) and audited operational resets. */
@RestController
public class LimitUsagesController implements LimitUsagesApi {
    private final LimitAdminService admin;
    private final Http http;

    public LimitUsagesController(LimitAdminService admin, Http http) {
        this.admin = admin;
        this.http = http;
    }

    @Override
    public ResponseEntity<LimitUsage> limitUsagesReset(UUID id, String idempotencyKey, LimitUsagesResetRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, LimitUsage.class, () -> http.ok(admin.resetUsage(id, reason), LimitUsage.class));
    }
}
