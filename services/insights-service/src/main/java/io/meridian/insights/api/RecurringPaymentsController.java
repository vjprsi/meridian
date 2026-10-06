package io.meridian.insights.api;

import io.meridian.insights.api.generated.RecurringPaymentsApi;
import io.meridian.insights.api.generated.model.RecurringPayment;
import io.meridian.insights.api.generated.model.RecurringPaymentsBlockCardChargesRequest;
import io.meridian.insights.api.generated.model.RecurringPaymentsDetectRequest;
import io.meridian.insights.application.Json;
import io.meridian.insights.application.RecurringService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Recurring payments: detection, customer actions and card merchant blocks. */
@RestController
public class RecurringPaymentsController implements RecurringPaymentsApi {
    private final RecurringService recurring;
    private final Http http;

    public RecurringPaymentsController(RecurringService recurring, Http http) {
        this.recurring = recurring;
        this.http = http;
    }

    @Override
    public ResponseEntity<RecurringPayment> recurringPaymentsConfirm(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "recurring.confirm", id, null, RecurringPayment.class, () -> recurring.transition(id, "confirm"));
    }

    @Override
    public ResponseEntity<RecurringPayment> recurringPaymentsDismiss(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "recurring.dismiss", id, null, RecurringPayment.class, () -> recurring.transition(id, "dismiss"));
    }

    @Override
    public ResponseEntity<RecurringPayment> recurringPaymentsBlockCardCharges(UUID id, String idempotencyKey,
                                                                            RecurringPaymentsBlockCardChargesRequest body) {
        JsonNode req = http.tree(body);
        UUID card = Json.uuid(req, "card_id");
        String merchant = Json.text(req, "merchant_ref");
        return http.ok(idempotencyKey, "recurring.block", id, body, RecurringPayment.class,
                () -> recurring.blockCardCharges(id, card, merchant));
    }

    @Override
    public ResponseEntity<Map<String, Object>> recurringPaymentsDetect(String idempotencyKey, RecurringPaymentsDetectRequest body) {
        JsonNode req = http.tree(body);
        UUID customer = Json.uuid(req, "customer_id");
        Integer lookback = req.hasNonNull("lookback_days") ? req.get("lookback_days").asInt() : null;
        return http.document(idempotencyKey, "recurring.detect", null, body, () -> recurring.detect(customer, lookback));
    }
}
