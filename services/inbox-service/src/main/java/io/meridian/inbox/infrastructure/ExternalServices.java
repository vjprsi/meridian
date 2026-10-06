package io.meridian.inbox.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Adapters to other Meridian services: CRM work items (crm-service), attachments (document-service), customer
 * alerts (notification-service) and segment membership (customer-service).
 */
@Component
public class ExternalServices {
    private static final Logger log = LoggerFactory.getLogger(ExternalServices.class);
    static final String CRM = "crm-service";
    static final String DOCUMENTS = "document-service";
    static final String NOTIFICATIONS = "notification-service";
    static final String CUSTOMERS = "customer-service";

    private final ServiceClient client;

    public ExternalServices(ServiceClient client) {
        this.client = client;
    }

    /**
     * Opens a CRM work item for a thread. Fail-open: the thread is the customer's record and must not be lost
     * because the CRM is unavailable; the problem is logged and the thread stays unrouted (work_item_id empty).
     */
    public Optional<UUID> openWorkItem(UUID queueId, UUID threadId, String title, String key) {
        try {
            JsonNode item = client.post(CRM, "/v1/servicing/work-items", Map.of(
                    "work_queue_id", queueId.toString(), "subject_type", "SECURE_MESSAGE_THREAD",
                    "subject_id", threadId.toString(), "title", title, "priority", "MEDIUM"), key);
            return Optional.ofNullable(item.path("id").asString(null)).map(UUID::fromString);
        } catch (RuntimeException e) {
            log.warn("CRM work item for thread {} not created: {}", threadId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Best-effort propagation of thread assignment / closure to its CRM work item. */
    public void workItemAction(UUID workItemId, String action, Object body, String key) {
        if (workItemId == null) {
            return;
        }
        try {
            client.post(CRM, "/v1/servicing/work-items/" + workItemId + "/" + action, body, key);
        } catch (RuntimeException e) {
            log.warn("CRM work item {} {} failed: {}", workItemId, action, e.getMessage());
        }
    }

    /**
     * Verifies an attachment: it must exist in document-service and be AVAILABLE (scanned clean). Fail-closed.
     */
    public void requireAvailableDocument(UUID documentId) {
        JsonNode doc;
        try {
            doc = client.get(DOCUMENTS, "/v1/documents/" + documentId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("attachment_not_found", "Document " + documentId + " does not exist");
            }
            throw ApiException.unprocessable("attachment_not_verified",
                    "Document " + documentId + " could not be verified: " + e.getMessage());
        }
        String status = doc.path("status").asString("");
        if (!"AVAILABLE".equals(status)) {
            throw ApiException.unprocessable("attachment_not_available",
                    "Document " + documentId + " is " + status + "; only AVAILABLE (virus-scanned) documents can be attached");
        }
    }

    /** Alerts the customer about a new secure message. Fail-open: returns empty when no notification was sent. */
    public Optional<UUID> notifyCustomer(UUID customerId, UUID threadId, String subject, String key) {
        try {
            JsonNode n = client.post(NOTIFICATIONS, "/v1/notifications/send-templated", Map.of(
                    "template_code", "SECURE_MESSAGE_RECEIVED", "recipient_type", "CUSTOMER",
                    "recipient_id", customerId.toString(), "channel", "PUSH", "category", "SERVICE",
                    "variables", Map.of("thread_id", threadId.toString(), "subject", subject),
                    "data", Map.of("deeplink", "app://inbox/threads/" + threadId)), key);
            return Optional.ofNullable(n.path("id").asString(null)).map(UUID::fromString);
        } catch (RuntimeException e) {
            log.warn("Secure message alert for thread {} not sent: {}", threadId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Active segment ids of a customer; null when they cannot be read (callers fail closed on targeted content). */
    public Set<String> customerSegments(UUID customerId) {
        try {
            JsonNode page = client.get(CUSTOMERS, "/v1/customers/" + customerId + "/segments?limit=200");
            Set<String> out = new HashSet<>();
            page.path("data").forEach(a -> {
                if (a.hasNonNull("customer_segment_id")) {
                    out.add(a.get("customer_segment_id").asString());
                }
            });
            return out;
        } catch (RuntimeException e) {
            log.warn("Segments of customer {} unavailable: {}", customerId, e.getMessage());
            return null;
        }
    }
}
