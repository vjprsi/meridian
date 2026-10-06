package io.meridian.audit.infrastructure;

import io.meridian.audit.application.DataExportService;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
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
 * Executes data exports when workflow-service reports their approval request as approved
 * ({@code platform.approval_request.approved}). The approver (updated_by of the approval) is the executing
 * principal, so the four-eyes rule is enforced again locally.
 */
@Component
public class ApprovalListener {
    private static final Logger log = LoggerFactory.getLogger(ApprovalListener.class);

    private final DataExportService exports;
    private final JsonMapper json;

    public ApprovalListener(DataExportService exports, JsonMapper json) {
        this.exports = exports;
        this.json = json;
    }

    @KafkaListener(topics = "${meridian.events.topic:meridian.platform.v1}", groupId = "audit-service-approvals",
            autoStartup = "${meridian.approvals.listener-enabled:true}")
    public void onEvent(ConsumerRecord<String, String> rec) throws Exception {
        if (!"platform.approval_request.approved".equals(header(rec, "ce_type")) || rec.value() == null) {
            return;
        }
        JsonNode approval = json.readTree(rec.value());
        if (!DataExportService.RESOURCE_TYPE.equals(approval.path("resource_type").asString(null))) {
            return;
        }
        String tenant = header(rec, "ce_tenantid");
        String approver = approval.hasNonNull("updated_by") ? approval.get("updated_by").asString() : "workflow-service";
        try {
            TenantContext.callAs(UUID.fromString(tenant), approver, () -> exports.applyApproved(approval));
        } catch (ApiException e) {
            // Business rejections are final (not retried); they are visible on the export and in the logs.
            log.warn("Approved export {} not executed: {} {}", approval.path("resource_id").asString(), e.code(), e.getMessage());
        }
    }

    private static String header(ConsumerRecord<String, String> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
