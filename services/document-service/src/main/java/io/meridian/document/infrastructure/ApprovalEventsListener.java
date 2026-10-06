package io.meridian.document.infrastructure;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.document.application.TemplateService;
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
 * Consumes approval outcomes from workflow-service (binary CloudEvents on {@code meridian.platform.v1}) and
 * activates or reverts document templates submitted for publishing. Idempotent: a template only changes while
 * it is PENDING_APPROVAL.
 */
@Component
@ConditionalOnProperty(name = "meridian.documents.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class ApprovalEventsListener {
    private static final Logger log = LoggerFactory.getLogger(ApprovalEventsListener.class);
    private static final Set<String> TYPES = Set.of("platform.approval_request.approved", "platform.approval_request.rejected",
            "platform.approval_request.executed", "platform.approval_request.expired",
            "platform.approval_request.changes_requested", "platform.approval_request.cancelled");

    private final TemplateService templates;
    private final JsonMapper json;

    public ApprovalEventsListener(TemplateService templates, JsonMapper json) {
        this.templates = templates;
        this.json = json;
    }

    @KafkaListener(topics = "${meridian.documents.approval-topic:meridian.platform.v1}", groupId = "document-service-approvals")
    public void onEvent(ConsumerRecord<String, String> record) throws Exception {
        String type = header(record, "ce_type");
        String tenant = header(record, "ce_tenantid");
        if (type == null || tenant == null || !TYPES.contains(type)) {
            return;
        }
        JsonNode payload = json.readTree(record.value());
        if (!"document_template".equals(payload.path("resource_type").asString("")) || !payload.hasNonNull("resource_id")) {
            return;
        }
        UUID templateId = UUID.fromString(payload.get("resource_id").asString());
        String status = payload.path("status").asString("");
        boolean changed = TenantContext.callAs(UUID.fromString(tenant), "workflow-service",
                () -> templates.onApprovalDecided(templateId, status));
        log.info("Approval {} for template {} -> changed={}", status, templateId, changed);
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
