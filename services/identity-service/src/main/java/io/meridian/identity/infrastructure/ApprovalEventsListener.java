package io.meridian.identity.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.identity.application.ApprovalExecutor;
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
 * Consumes {@code platform.approval_request.approved} CloudEvents from the platform topic and executes the approved
 * identity operation in the event's tenant. Business rejections are logged (the approval stays APPROVED and can be
 * re-driven); delivery is at-least-once and execution is idempotent.
 */
@Component
public class ApprovalEventsListener {
    public static final String APPROVED = "platform.approval_request.approved";
    private static final Logger log = LoggerFactory.getLogger(ApprovalEventsListener.class);

    private final ApprovalExecutor executor;
    private final JsonMapper json;

    public ApprovalEventsListener(ApprovalExecutor executor, JsonMapper json) {
        this.executor = executor;
        this.json = json;
    }

    @KafkaListener(topics = "${meridian.events.topic}", groupId = "identity-service-approvals",
            autoStartup = "${meridian.approvals.listener-enabled:true}")
    public void on(ConsumerRecord<String, String> rec) throws Exception {
        if (!APPROVED.equals(header(rec, "ce_type"))) {
            return;
        }
        String tenant = header(rec, "ce_tenantid");
        if (tenant == null || rec.value() == null) {
            return;
        }
        JsonNode approval = json.readTree(rec.value());
        if (!ApprovalExecutor.handles(approval)) {
            return;
        }
        try {
            TenantContext.callAs(UUID.fromString(tenant), "workflow-service", () -> executor.applyApproved(approval));
        } catch (ApiException e) {
            log.warn("Approved request {} not executed: {} {}", approval.path("id").asString(""), e.code(), e.getMessage());
        }
    }

    private static String header(ConsumerRecord<String, String> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
