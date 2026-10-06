package io.meridian.fraud.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.fraud.api.generated.FraudScoresApi;
import io.meridian.fraud.api.generated.model.FraudFeedback;
import io.meridian.fraud.api.generated.model.FraudRuleHitPage;
import io.meridian.fraud.api.generated.model.FraudScoresEvaluateRequest;
import io.meridian.fraud.api.generated.model.FraudScoresFeedbackRequest;
import io.meridian.fraud.application.EvaluationService;
import io.meridian.fraud.application.ExplanationService;
import io.meridian.fraud.application.FraudAlertService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Real-time scoring, explanations, rule hits and outcome labels. */
@RestController
public class FraudScoresController implements FraudScoresApi {
    private final EvaluationService evaluation;
    private final ExplanationService explanation;
    private final FraudAlertService alerts;
    private final CrudEngine crud;
    private final Http http;

    public FraudScoresController(EvaluationService evaluation, ExplanationService explanation, FraudAlertService alerts,
                                 CrudEngine crud, Http http) {
        this.evaluation = evaluation;
        this.explanation = explanation;
        this.alerts = alerts;
        this.crud = crud;
        this.http = http;
    }

    /** Idempotent by the Idempotency-Key stored on the score itself (no extra round trips on the hot path). */
    @Override
    public ResponseEntity<Map<String, Object>> fraudScoresEvaluate(String idempotencyKey, FraudScoresEvaluateRequest body) {
        Map<String, Object> result = evaluation.evaluate(http.tree(body), idempotencyKey);
        ResponseEntity.BodyBuilder b = ResponseEntity.ok();
        if (Boolean.TRUE.equals(result.get("replayed"))) {
            b.header("Idempotent-Replayed", "true");
        }
        return b.body(result);
    }

    @Override
    public ResponseEntity<Map<String, Object>> fraudScoresExplanation(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok(explanation.explain(id));
    }

    @Override
    public ResponseEntity<FraudFeedback> fraudScoresFeedback(UUID id, String idempotencyKey, FraudScoresFeedbackRequest body) {
        return http.once(idempotencyKey, body, FraudFeedback.class, () ->
                http.respond(HttpStatus.OK, alerts.recordFeedback(id, (ObjectNode) http.tree(body)), FraudFeedback.class));
    }

    @Override
    public ResponseEntity<FraudRuleHitPage> fraudScoresRuleHits(UUID id, Integer limit, String cursor) {
        crud.get("fraud_score", Object.class, Crud.params().with("id", id));
        return crud.list("fraud_rule_hit", FraudRuleHitPage.class,
                Crud.params().with("fraud_score_id", id).with("limit", limit).with("cursor", cursor));
    }
}
