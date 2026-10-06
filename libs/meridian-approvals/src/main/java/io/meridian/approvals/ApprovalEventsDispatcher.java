package io.meridian.approvals;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code platform.approval_request.*} CloudEvents from workflow-service and hands final decisions for
 * this service's resource types to their {@link ApprovalOutcomeHandler}. Business rejections by the handler
 * ({@link ApiException}) are logged and the event is acknowledged; other failures are retried by the container.
 */
public class ApprovalEventsDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ApprovalEventsDispatcher.class);
    private static final String PREFIX = "platform.approval_request.";

    private final Map<String, ApprovalOutcomeHandler> handlers;
    private final JsonMapper json;

    public ApprovalEventsDispatcher(List<ApprovalOutcomeHandler> handlers, JsonMapper json) {
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(ApprovalOutcomeHandler::resourceType, Function.identity()));
        this.json = json;
    }

    @KafkaListener(topics = "${meridian.approvals.topic:meridian.platform.v1}",
            groupId = "${spring.application.name}-approvals")
    public void onEvent(ConsumerRecord<String, String> record) throws Exception {
        String type = header(record, "ce_type");
        String tenant = header(record, "ce_tenantid");
        if (type == null || tenant == null || !type.startsWith(PREFIX)) {
            return;
        }
        ApprovalDecision.Outcome outcome = ApprovalDecision.Outcome.ofEventSuffix(type.substring(PREFIX.length()));
        if (outcome != null) {
            dispatch(UUID.fromString(tenant), json.readTree(record.value()), outcome);
        }
    }

    /** Routes one decision to its handler. Returns false when no handler owns the resource type. */
    public boolean dispatch(UUID tenant, JsonNode row, ApprovalDecision.Outcome outcome) throws Exception {
        ApprovalOutcomeHandler handler = handlers.get(row.path("resource_type").asString(""));
        if (handler == null) {
            return false;
        }
        String decidedBy = row.path("updated_by").asString(row.path("maker").asString("workflow-service"));
        ApprovalDecision decision = new ApprovalDecision(uuid(row, "id"), row.path("resource_type").asString(),
                uuid(row, "resource_id"), row.path("action").asString(null), outcome,
                row.path("maker").asString(null), decidedBy, row.path("payload"), row);
        try {
            TenantContext.callAs(tenant, decidedBy, () -> {
                handler.onDecided(decision);
                return null;
            });
            log.info("Approval {} {} for {} {} applied", decision.approvalRequestId(), outcome,
                    decision.resourceType(), decision.resourceId());
        } catch (ApiException e) {
            log.warn("Approval {} {} for {} {} not applied: {} {}", decision.approvalRequestId(), outcome,
                    decision.resourceType(), decision.resourceId(), e.code(), e.getMessage());
        }
        return true;
    }

    private static UUID uuid(JsonNode n, String field) {
        return n.hasNonNull(field) ? UUID.fromString(n.get(field).asString()) : null;
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
