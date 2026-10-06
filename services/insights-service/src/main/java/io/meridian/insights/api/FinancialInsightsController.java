package io.meridian.insights.api;

import io.meridian.insights.api.generated.FinancialInsightsApi;
import io.meridian.insights.api.generated.model.FinancialInsight;
import io.meridian.insights.api.generated.model.FinancialInsightsFeedbackRequest;
import io.meridian.insights.api.generated.model.FinancialInsightsGenerateRequest;
import io.meridian.insights.application.InsightService;
import io.meridian.insights.application.Json;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Insight cards: lifecycle, feedback and on-demand generation. */
@RestController
public class FinancialInsightsController implements FinancialInsightsApi {
    private final InsightService insights;
    private final Http http;

    public FinancialInsightsController(InsightService insights, Http http) {
        this.insights = insights;
        this.http = http;
    }

    @Override
    public ResponseEntity<FinancialInsight> financialInsightsSeen(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "insight.seen", id, null, FinancialInsight.class, () -> insights.transition(id, "seen"));
    }

    @Override
    public ResponseEntity<FinancialInsight> financialInsightsDismiss(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "insight.dismiss", id, null, FinancialInsight.class, () -> insights.transition(id, "dismiss"));
    }

    @Override
    public ResponseEntity<FinancialInsight> financialInsightsActed(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "insight.acted", id, null, FinancialInsight.class, () -> insights.transition(id, "acted"));
    }

    @Override
    public ResponseEntity<FinancialInsight> financialInsightsFeedback(UUID id, String idempotencyKey,
                                                                    FinancialInsightsFeedbackRequest body) {
        boolean useful = Json.bool(http.tree(body), "feedback_useful");
        return http.ok(idempotencyKey, "insight.feedback", id, body, FinancialInsight.class, () -> insights.feedback(id, useful));
    }

    @Override
    public ResponseEntity<Map<String, Object>> financialInsightsGenerate(String idempotencyKey, FinancialInsightsGenerateRequest body) {
        JsonNode req = http.tree(body);
        UUID customer = Json.uuid(req, "customer_id");
        UUID account = Json.uuid(req, "deposit_account_id");
        return http.document(idempotencyKey, "insight.generate", null, body, () -> insights.generate(customer, account));
    }
}
