package io.meridian.tenant.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.application.ModuleService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Executes maker-checker module changes once workflow-service publishes {@code platform.approval_request.approved}
 * (CloudEvents binary mode on the platform topic). Business rejections at execution time are logged and skipped;
 * re-deliveries are harmless because execution is idempotent.
 */
@Component
public class ApprovalEventListener {
    private static final Logger log = LoggerFactory.getLogger(ApprovalEventListener.class);

    private final ModuleService modules;
    private final JsonMapper json;

    public ApprovalEventListener(ModuleService modules, JsonMapper json) {
        this.modules = modules;
        this.json = json;
    }

    @KafkaListener(topics = "${meridian.events.topic}", groupId = "tenant-service-approvals",
            autoStartup = "${meridian.approvals.listener-enabled:true}")
    public void onEvent(ConsumerRecord<String, String> record) {
        if (!"platform.approval_request.approved".equals(header(record, "ce_type"))) {
            return;
        }
        JsonNode approval = json.readTree(record.value());
        if (!ModuleService.RESOURCE_TYPE.equals(approval.path("resource_type").asString())) {
            return;
        }
        String tenant = header(record, "ce_tenantid");
        if (tenant == null) {
            log.warn("Approved request {} without tenant header ignored", approval.path("id").asString());
            return;
        }
        try {
            TenantContext.callAs(UUID.fromString(tenant), "workflow-service", () -> modules.applyApproved(approval));
        } catch (ApiException e) {
            log.warn("Approved module change {} not applied: {} {}", approval.path("id").asString(), e.code(), e.getMessage());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
