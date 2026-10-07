package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyInsightsApi;
import io.meridian.customerchannel.api.generated.model.FinancialInsight;
import io.meridian.customerchannel.api.generated.model.FinancialInsightPage;
import io.meridian.customerchannel.api.generated.model.SpendCategoryPage;
import io.meridian.customerchannel.application.EngagementService;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Spending analytics and insight cards. */
@RestController
public class MyInsightsController implements MyInsightsApi {
    private final EngagementService engagement;
    private final Http http;

    public MyInsightsController(EngagementService engagement, Http http) {
        this.engagement = engagement;
        this.http = http;
    }

    @Override
    public ResponseEntity<Map<String, Object>> myInsightsSpending(LocalDate from, LocalDate to, String currency) {
        return http.object(engagement.spending(from, to, currency));
    }

    @Override
    public ResponseEntity<SpendCategoryPage> myInsightsCategories(Integer limit, String cursor) {
        return http.ok(engagement.categories(limit, cursor), SpendCategoryPage.class);
    }

    @Override
    public ResponseEntity<FinancialInsightPage> myInsightsCards(Integer limit, String cursor) {
        return http.ok(engagement.insightCards(limit, cursor), FinancialInsightPage.class);
    }

    @Override
    public ResponseEntity<FinancialInsight> myInsightsCardsDismiss(UUID insightId, String idempotencyKey) {
        return http.ok(engagement.dismissInsight(insightId, idempotencyKey), FinancialInsight.class);
    }
}
