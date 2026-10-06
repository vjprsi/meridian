package io.meridian.archive.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Central maker-checker for purge requests (G11): every time a purge enters PENDING_APPROVAL (submit, or a blocking
 * legal hold released) an approval request is opened in workflow-service. The row_version of the PENDING_APPROVAL
 * row is the fingerprint: any later change to the request makes an outstanding decision stale.
 */
@Component
public class PurgeApprovals {
    /** Approval resource type for purge requests in workflow-service. */
    public static final String RESOURCE = "purge_request";
    static final String ACTION = "approve";

    private final Approvals approvals;

    public PurgeApprovals(Approvals approvals) {
        this.approvals = approvals;
    }

    /** Opens the approval request for a PENDING_APPROVAL purge row (no-op in local mode). */
    void request(JsonNode row) {
        if (row == null || !"PENDING_APPROVAL".equals(row.path("status").asString())) {
            return;
        }
        UUID id = UUID.fromString(row.get("id").asString());
        int rowVersion = row.path("row_version").asInt();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("purge_request_id", id.toString());
        payload.put("action", row.path("action").asString(null));
        payload.put("scope_description", row.path("scope_description").asString(null));
        payload.put("record_selector", row.get("record_selector"));
        payload.put("data_classification_id", row.path("data_classification_id").asString(null));
        payload.put("retention_policy_id", row.path("retention_policy_id").asString(null));
        payload.put("estimated_records", row.path("estimated_records").isNumber() ? row.get("estimated_records").asLong() : null);
        payload.put("requested_by", row.path("created_by").asString(null));
        payload.put("row_version", rowVersion);
        String title = "Purge (" + row.path("action").asString() + "): " + row.path("scope_description").asString("");
        approvals.request(ApprovalSpec.of(RESOURCE, id, ACTION, title.length() > 200 ? title.substring(0, 200) : title, payload)
                        .withSummary("Permanently " + ("PURGE".equals(row.path("action").asString()) ? "delete" : "anonymise")
                                + " data: " + row.path("scope_description").asString("")),
                "submit:" + id + ":" + rowVersion);
    }

    /** Closes open inbox items after the purge was decided (or cancelled) in archive-service; after commit. */
    void decided(UUID id, String reason) {
        approvals.cancelOpen(RESOURCE, id, null, reason);
    }
}
