package io.meridian.collections.infrastructure;

import io.meridian.collections.application.CaseService;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opens, refreshes or cures collections cases from {@code lending.loan.delinquency_changed} (binary CloudEvents:
 * ce_type, ce_tenantid headers; payload = loan_status_history row with the amounts). Naturally idempotent: a
 * redelivered event refreshes the case to the same values.
 */
@Component
@ConditionalOnProperty(name = "meridian.collections.delinquency-consumer-enabled", havingValue = "true", matchIfMissing = true)
public class DelinquencyListener {
    private static final Logger log = LoggerFactory.getLogger(DelinquencyListener.class);
    static final String TYPE = "lending.loan.delinquency_changed";

    private final CaseService cases;
    private final JsonMapper json;

    public DelinquencyListener(CaseService cases, JsonMapper json) {
        this.cases = cases;
        this.json = json;
    }

    @KafkaListener(topics = "${meridian.collections.delinquency-topic:meridian.lending.v1}", groupId = "collections-service-delinquency")
    public void onEvent(ConsumerRecord<String, String> record) throws Exception {
        String type = header(record, "ce_type");
        String tenant = header(record, "ce_tenantid");
        if (!TYPE.equals(type) || tenant == null || record.value() == null) {
            return;
        }
        JsonNode p = json.readTree(record.value());
        if (!p.hasNonNull("loan_id") || !p.hasNonNull("customer_id") || !p.hasNonNull("currency")) {
            log.warn("delinquency event {} without loan, customer or currency ignored", header(record, "ce_id"));
            return;
        }
        CaseService.Signal s = new CaseService.Signal(UUID.fromString(p.get("customer_id").asString()), "LOAN",
                UUID.fromString(p.get("loan_id").asString()), p.path("days_past_due").asInt(0),
                dec(p, "overdue_amount"), dec(p, "total_outstanding"), p.get("currency").asString(), null, null);
        TenantContext.callAs(UUID.fromString(tenant), "collections-service", () -> {
            try {
                return cases.openOrRefresh(s);
            } catch (ApiException e) {
                log.debug("delinquency event not applied: {}", e.getMessage());
                return null;
            }
        });
    }

    private static BigDecimal dec(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? BigDecimal.ZERO : new BigDecimal(v.asString());
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
