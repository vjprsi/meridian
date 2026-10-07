package io.meridian.payment.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.application.PaymentProcessor;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies fraud analysts' outcomes to payments held for review: {@code risk.fraud_alert.resolved} (binary CloudEvents:
 * ce_type, ce_tenantid; payload = fraud_alert row) of a PAYMENT alert releases the payment (FALSE_POSITIVE, CLOSED) or
 * rejects it (CONFIRMED_FRAUD). Idempotent: an alert whose payment is no longer held (already applied, cancelled, or
 * the alert of a blocked payment) is ignored; downstream outages propagate so the container redelivers.
 */
@Component
@ConditionalOnProperty(name = "meridian.payment.fraud.alert-consumer-enabled", havingValue = "true", matchIfMissing = true)
public class FraudAlertListener {
    private static final Logger log = LoggerFactory.getLogger(FraudAlertListener.class);
    static final String TYPE = "risk.fraud_alert.resolved";

    private final PaymentProcessor processor;
    private final JsonMapper json;

    public FraudAlertListener(PaymentProcessor processor, JsonMapper json) {
        this.processor = processor;
        this.json = json;
    }

    @KafkaListener(id = "payment-fraud-alerts", topics = "${meridian.payment.fraud.alert-topic:meridian.risk.v1}",
            groupId = "${meridian.payment.fraud.alert-group:payment-service-fraud-alerts}",
            properties = {"auto.offset.reset=earliest"})
    public void onEvent(ConsumerRecord<String, String> record) throws Exception {
        String type = header(record, "ce_type");
        String tenant = header(record, "ce_tenantid");
        if (!TYPE.equals(type) || tenant == null || record.value() == null) {
            return;
        }
        UUID tenantId;
        JsonNode alert;
        try {
            tenantId = UUID.fromString(tenant);
            alert = json.readTree(record.value());
        } catch (IllegalArgumentException | JacksonException e) {
            log.warn("fraud alert event {} skipped: {}", header(record, "ce_id"), e.getMessage());
            return;
        }
        apply(tenantId, alert);
    }

    /**
     * Applies one resolved alert under its tenant (also used by tests); returns the payment's new status, empty when
     * the alert is not about a held payment.
     */
    public Optional<String> apply(UUID tenant, JsonNode alert) throws Exception {
        if (!"PAYMENT".equals(alert.path("subject_type").asString(null)) || !alert.hasNonNull("subject_id")) {
            return Optional.empty();
        }
        String decision = switch (alert.path("status").asString("")) {
            case "CONFIRMED_FRAUD" -> "REJECT";
            case "FALSE_POSITIVE", "CLOSED" -> "RELEASE";
            default -> null;
        };
        if (decision == null) {
            return Optional.empty();
        }
        UUID paymentId;
        UUID alertId;
        try {
            paymentId = UUID.fromString(alert.get("subject_id").asString());
            alertId = alert.hasNonNull("id") ? UUID.fromString(alert.get("id").asString()) : null;
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String analyst = alert.path("resolved_by").asString(null);
        String principal = analyst == null ? "fraud-review" : ("fraud-review:" + analyst);
        String reason = "fraud alert " + alertId + " " + alert.path("status").asString()
                + (alert.hasNonNull("resolution") ? ": " + alert.get("resolution").asString() : "");
        return TenantContext.callAs(tenant, principal.length() > 64 ? principal.substring(0, 64) : principal, () -> {
            try {
                return Optional.of(processor.fraudReviewDecision(paymentId, decision, alertId, reason)
                        .path("status").asString());
            } catch (ApiException e) {
                if (e.status() >= 500) {
                    throw e;
                }
                log.debug("fraud alert {} not applied to payment {}: {}", alertId, paymentId, e.getMessage());
                return Optional.<String>empty();
            }
        });
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
