package io.meridian.insights.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.insights.api.generated.SpendingSummariesApi;
import io.meridian.insights.application.SpendingService;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Spending analytics computed from current enrichments. */
@RestController
public class SpendingSummariesController implements SpendingSummariesApi {
    private final SpendingService spending;
    private final Http http;

    public SpendingSummariesController(SpendingService spending, Http http) {
        this.spending = spending;
        this.http = http;
    }

    @Override
    public ResponseEntity<Map<String, Object>> spendingSummariesByCategory(UUID customerId, LocalDate from, LocalDate to,
                                                                          String currency) {
        TenantContext.require();
        return http.query(spending.byCategory(customerId, from, to, currency));
    }

    @Override
    public ResponseEntity<Map<String, Object>> spendingSummariesByMerchant(UUID customerId, LocalDate from, LocalDate to,
                                                                          String currency, Integer limit) {
        TenantContext.require();
        return http.query(spending.byMerchant(customerId, from, to, currency, limit));
    }

    @Override
    public ResponseEntity<Map<String, Object>> spendingSummariesTrend(UUID customerId, Integer months, String currency) {
        TenantContext.require();
        return http.query(spending.trend(customerId, months, currency));
    }
}
