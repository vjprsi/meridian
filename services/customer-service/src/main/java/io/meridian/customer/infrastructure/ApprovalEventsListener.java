package io.meridian.customer.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.customer.application.CustomerService;
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
 * Consumes maker-checker outcomes from workflow-service (binary CloudEvents on {@code meridian.platform.v1}) and
 * applies or drops pending KYC tier upgrades. Idempotent: only the approval request currently linked to the
 * customer ({@code tier_approval_request_id}) has an effect, so redeliveries are harmless.
 */
@Component
@ConditionalOnProperty(name = "meridian.approvals.listener-enabled", havingValue = "true", matchIfMissing = true)
public class ApprovalEventsListener {
    private static final Logger log = LoggerFactory.getLogger(ApprovalEventsListener.class);
    static final Set<String> TYPES = Set.of("platform.approval_request.approved", "platform.approval_request.rejected",
            "platform.approval_request.expired", "platform.approval_request.cancelled");

    private final CustomerService customers;
    private final JsonMapper json;

    public ApprovalEventsListener(CustomerService customers, JsonMapper json) {
        this.customers = customers;
        this.json = json;
    }

    @KafkaListener(topics = "${meridian.approvals.topic:meridian.platform.v1}", groupId = "customer-service-approvals")
    public void onEvent(ConsumerRecord<String, String> record) throws Exception {
        String type = header(record, "ce_type");
        String tenant = header(record, "ce_tenantid");
        if (type == null || tenant == null || !TYPES.contains(type)) {
            return;
        }
        JsonNode approval = json.readTree(record.value());
        if (!CustomerService.TIER_APPROVAL_RESOURCE.equals(approval.path("resource_type").asString(""))) {
            return;
        }
        try {
            boolean changed = TenantContext.callAs(UUID.fromString(tenant), "workflow-service",
                    () -> customers.onTierApprovalDecided(approval, type.substring(type.lastIndexOf('.') + 1)));
            log.info("Approval {} ({}) for customer {} -> changed={}", approval.path("id").asString(), type,
                    approval.path("resource_id").asString(), changed);
        } catch (ApiException e) {
            log.warn("Approved tier change {} not applied: {} {}", approval.path("id").asString(), e.code(), e.getMessage());
        }
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
