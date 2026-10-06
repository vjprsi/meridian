package io.meridian.commission.infrastructure;

import io.meridian.commission.application.AccrualService;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.Set;
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
 * Accrues commissions from wallet-service events (binary CloudEvents on {@code meridian.wallets.v1}): completed
 * transactions accrue, reversed ones are clawed back. Accrual is idempotent per transaction, so redeliveries are harmless.
 */
@Component
@ConditionalOnProperty(name = "meridian.commission.listener-enabled", havingValue = "true", matchIfMissing = true)
public class WalletEventsListener {
    private static final Logger log = LoggerFactory.getLogger(WalletEventsListener.class);
    static final Set<String> TYPES = Set.of("wallets.wallet_transaction.completed", "wallets.wallet_transaction.reversed");

    private final AccrualService accruals;
    private final JsonMapper json;

    public WalletEventsListener(AccrualService accruals, JsonMapper json) {
        this.accruals = accruals;
        this.json = json;
    }

    @KafkaListener(topics = "${meridian.commission.wallet-topic:meridian.wallets.v1}", groupId = "commission-service-accrual")
    public void onEvent(ConsumerRecord<String, String> record) throws Exception {
        String type = header(record, "ce_type");
        String tenant = header(record, "ce_tenantid");
        if (type == null || tenant == null || !TYPES.contains(type)) {
            return;
        }
        JsonNode txn = json.readTree(record.value());
        if (type.endsWith(".completed") && !"COMPLETED".equals(txn.path("status").asString())) {
            return;
        }
        try {
            TenantContext.callAs(UUID.fromString(tenant), "wallet-events",
                    () -> accruals.accrue(AccrualService.TxnEvent.from(txn, "id")));
        } catch (ApiException e) {
            log.warn("Commission accrual for {} skipped: {} {}", txn.path("id").asString(), e.code(), e.getMessage());
        }
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
