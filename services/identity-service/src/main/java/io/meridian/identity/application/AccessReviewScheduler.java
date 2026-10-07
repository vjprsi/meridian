package io.meridian.identity.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.web.client.ServiceClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * GAP-13: opens the quarter's access review ({@code 2026-Q4}) for every ACTIVE tenant on the first day of each quarter
 * (cron {@code meridian.identity.access-review.cron}, UTC), due {@code due-days} later; a tenant that already has the
 * period's review is left alone. Off with {@code meridian.identity.access-review.enabled=false}. The review then goes
 * through attest / revoke and a second person's approval in workflow-service ({@link AccessReviewService}).
 */
@Component
@ConditionalOnProperty(name = "meridian.identity.access-review.enabled", havingValue = "true", matchIfMissing = true)
public class AccessReviewScheduler {
    private static final Logger log = LoggerFactory.getLogger(AccessReviewScheduler.class);

    private final AccessReviewService reviews;
    private final ServiceClient services;
    private final int dueDays;
    private final int dormantDays;

    public AccessReviewScheduler(AccessReviewService reviews, ServiceClient services,
                                 @Value("${meridian.identity.access-review.due-days:30}") int dueDays,
                                 @Value("${meridian.identity.access-review.dormant-days:90}") int dormantDays) {
        this.reviews = reviews;
        this.services = services;
        this.dueDays = dueDays;
        this.dormantDays = dormantDays;
    }

    @Scheduled(cron = "${meridian.identity.access-review.cron:0 0 6 1 1,4,7,10 *}", zone = "UTC")
    public void quarterly() {
        LocalDate today = Clocks.now().toLocalDate();
        for (UUID tenant : tenants()) {
            openFor(tenant, today);
        }
    }

    /** Opens the review of {@code day}'s quarter for one tenant unless it exists (true when one was opened). */
    public boolean openFor(UUID tenant, LocalDate day) {
        String period = AccessReviewService.quarterOf(day);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("period", period);
        body.put("due_date", day.plusDays(dueDays).toString());
        body.put("dormant_days", dormantDays);
        try {
            TenantContext.callAs(tenant, "job:access-review", () -> reviews.open(body));
            log.info("Access review {} opened for tenant {}", period, tenant);
            return true;
        } catch (io.meridian.common.error.ApiException e) {
            if (!"duplicate_period".equals(e.code())) {
                log.error("Access review {} could not be opened for tenant {}: {}", period, tenant, e.getMessage());
            }
            return false;
        } catch (Exception e) {
            log.error("Access review {} could not be opened for tenant {}: {}", period, tenant, e.toString());
            return false;
        }
    }

    private Set<UUID> tenants() {
        Set<UUID> out = new LinkedHashSet<>();
        try {
            String cursor = null;
            for (int page = 0; page < 100; page++) {
                String path = "/v1/platform/tenants?status=ACTIVE&limit=200"
                        + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
                JsonNode res = services.get("tenant-service", path);
                if (res == null) {
                    break;
                }
                res.path("data").forEach(t -> {
                    if (t.hasNonNull("id")) {
                        out.add(UUID.fromString(t.get("id").asString()));
                    }
                });
                if (!res.path("has_more").asBoolean(false) || !res.hasNonNull("next_cursor")) {
                    break;
                }
                cursor = res.get("next_cursor").asString();
            }
        } catch (RuntimeException e) {
            log.error("tenant-service unavailable: quarterly access reviews not opened ({})", e.toString());
        }
        return out;
    }
}
