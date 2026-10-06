package io.meridian.fraud.api;

import io.meridian.common.error.ApiException;
import io.meridian.fraud.api.generated.FraudFeedbackApi;
import io.meridian.fraud.api.generated.model.FraudFeedback;
import io.meridian.fraud.api.generated.model.FraudFeedbackCreate;
import io.meridian.fraud.application.FraudAlertService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Outcome labels (chargebacks, consortium data, analyst decisions) for model training and rule tuning. */
@RestController
public class FraudFeedbackController implements FraudFeedbackApi {
    private final FraudAlertService alerts;
    private final Http http;

    public FraudFeedbackController(FraudAlertService alerts, Http http) {
        this.alerts = alerts;
        this.http = http;
    }

    @Override
    public ResponseEntity<FraudFeedback> fraudFeedbackCreate(String idempotencyKey, FraudFeedbackCreate body) {
        JsonNode req = http.tree(body);
        String scoreId = Http.text(req, "fraud_score_id");
        if (scoreId == null) {
            throw ApiException.unprocessable("fraud_score_id_required", "fraud_score_id is required");
        }
        return http.once(idempotencyKey, body, FraudFeedback.class, () -> http.respond(HttpStatus.CREATED,
                alerts.recordFeedback(UUID.fromString(scoreId), (ObjectNode) req), FraudFeedback.class));
    }
}
