package io.meridian.notification.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.notification.domain.DeliveryPolicy;
import io.meridian.notification.domain.TemplateRenderer;
import io.meridian.notification.infrastructure.NotificationStore;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.crud.CrudEngine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Template lifecycle: DRAFT (editable) -> PENDING_APPROVAL (publish, via workflow-service maker-checker) -> ACTIVE
 * (approved; the previously active version of the same code/channel/locale is RETIRED) or back to DRAFT (rejected).
 * A new version of an existing template is created by POSTing the same code/channel/locale again.
 *
 * <p>The publish approval goes through libs/meridian-approvals (G11). There is no local decision endpoint, so
 * publishing always opens the request in workflow-service whatever {@code meridian.approvals.mode} says; the
 * decision comes back through {@link NotificationTemplateApprovalHandler}.
 */
@Service
public class TemplateService {
    private static final Logger log = LoggerFactory.getLogger(TemplateService.class);
    public static final String RESOURCE_TYPE = "notification_template";

    private final NotificationStore store;
    private final CrudEngine crud;
    private final Approvals approvals;
    private final NotificationService notifications;
    private final JsonMapper json;

    public TemplateService(NotificationStore store, CrudEngine crud, ServiceClient services,
                           @Value("${spring.application.name}") String application,
                           NotificationService notifications, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.approvals = new Approvals(services, application, "workflow");
        this.notifications = notifications;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode values = crud.toColumns("notification_template", body);
        validateSyntax(values);
        if (!values.hasNonNull("locale")) {
            values.put("locale", "en");
        }
        values.put("status", "DRAFT");
        values.put("version", store.nextTemplateVersion(values.get("code").asString(), values.get("channel").asString(),
                values.get("locale").asString()));
        return crud.insert("notification_template", values);
    }

    /** Checks the update is allowed (DRAFT only; status and version are read-only) and returns the column values. */
    @Transactional(readOnly = true)
    public ObjectNode prepareUpdate(UUID id, Object body) {
        JsonNode current = json.readTree(store.json("notification_template", id)
                .orElseThrow(() -> ApiException.notFound("notification template", id)));
        ObjectNode values = crud.toColumns("notification_template", body);
        if (!"DRAFT".equals(current.get("status").asString())) {
            throw ApiException.conflict("template_not_editable",
                    "Only DRAFT templates can be edited; create a new version instead (template is "
                            + current.get("status").asString() + ")");
        }
        validateSyntax(values);
        return values;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preview(UUID id, Map<String, Object> variables) {
        JsonNode t = template(id);
        String channel = t.get("channel").asString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("template_id", id.toString());
        out.put("code", t.get("code").asString());
        out.put("channel", channel);
        out.put("locale", t.get("locale").asString());
        out.put("version", t.get("version").asInt());
        try {
            out.put("subject", TemplateRenderer.render(text(t, "subject"), variables, false, true));
            out.put("body", TemplateRenderer.render(t.get("body").asString(), variables, "EMAIL".equals(channel), true));
        } catch (TemplateRenderer.MissingVariablesException e) {
            throw NotificationService.missingVariables(e);
        }
        Set<String> used = TemplateRenderer.variables(t.get("body").asString());
        used.addAll(TemplateRenderer.variables(text(t, "subject")));
        out.put("variables", used);
        return out;
    }

    /** Moves a DRAFT to PENDING_APPROVAL and opens a maker-checker request in workflow-service. */
    @Transactional
    public JsonNode publish(UUID id, String idempotencyKey) {
        JsonNode t = json.readTree(store.lockTemplate(id).orElseThrow(() -> ApiException.notFound("notification template", id)));
        String status = t.get("status").asString();
        if (!"DRAFT".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Only DRAFT templates can be published; template is " + status);
        }
        store.setTemplateStatus(id, "PENDING_APPROVAL");
        ObjectNode payload = json.createObjectNode();
        payload.put("template_id", id.toString());
        payload.put("code", t.get("code").asString());
        payload.put("channel", t.get("channel").asString());
        payload.put("locale", t.get("locale").asString());
        payload.put("version", t.get("version").asInt());
        payload.put("category", t.get("category").asString());
        if (t.hasNonNull("subject")) {
            payload.put("subject", t.get("subject").asString());
        }
        payload.put("body", t.get("body").asString());
        payload.put("checksum", checksum(t));
        return approvals.request(ApprovalSpec.of(RESOURCE_TYPE, id, "publish",
                "Publish notification template " + t.get("code").asString() + " v" + t.get("version").asInt()
                        + " (" + t.get("channel").asString() + "/" + t.get("locale").asString() + ")", payload)
                .withSummary("Activate version " + t.get("version").asInt() + " of " + t.get("code").asString()
                        + "; the currently active version will be retired."), "publish:" + id + ":" + idempotencyKey);
    }

    /**
     * A decision from workflow-service on a publish request: ignored unless the template is still PENDING_APPROVAL
     * with the submitted content (checksum); an approval by the template's maker is refused
     * ({@code four_eyes_violation}).
     */
    @Transactional
    public void onWorkflowDecision(UUID templateId, String checksum, String approvalStatus, String maker) {
        JsonNode t = store.lockTemplate(templateId).map(json::readTree).orElse(null);
        if (t == null || !"PENDING_APPROVAL".equals(t.get("status").asString())
                || (checksum != null && !checksum.equals(checksum(t)))) {
            return;
        }
        String principal = TenantContext.principal();
        if ("APPROVED".equals(approvalStatus)
                && (principal.equals(maker) || principal.equals(t.path("created_by").asString(null)))) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The maker of a template cannot approve its publication; another user must act as checker");
        }
        onApprovalDecided(templateId, approvalStatus);
    }

    /** Content fingerprint of a template version (what approvers saw). */
    static String checksum(JsonNode t) {
        String content = t.path("subject").asString("") + "\u0000" + t.path("body").asString("") + "\u0000"
                + t.path("variables_schema").toString() + "\u0000" + t.path("category").asString("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Applies a maker-checker outcome for a template (from workflow-service decisions). Approved or executed
     * activates the template and retires the previous active version; rejected, changes requested, cancelled or
     * expired returns it to
     * DRAFT. Ignored unless the template is PENDING_APPROVAL (events are delivered at least once).
     */
    @Transactional
    public void onApprovalDecided(UUID templateId, String approvalStatus) {
        var row = store.lockTemplate(templateId);
        if (row.isEmpty()) {
            log.warn("Approval outcome for unknown template {}", templateId);
            return;
        }
        JsonNode t = json.readTree(row.get());
        if (!"PENDING_APPROVAL".equals(t.get("status").asString())) {
            return;
        }
        switch (approvalStatus) {
            case "APPROVED", "EXECUTED" -> {
                store.retireOtherActive(templateId, t.get("code").asString(), t.get("channel").asString(),
                        t.get("locale").asString());
                store.setTemplateStatus(templateId, "ACTIVE");
            }
            case "REJECTED", "CHANGES_REQUESTED", "CANCELLED", "EXPIRED" -> store.setTemplateStatus(templateId, "DRAFT");
            default -> { }
        }
    }

    /** Sends the template (any non-retired version) to a test destination with sample data. */
    public String testSend(UUID id, String destination) {
        JsonNode t = template(id);
        if ("RETIRED".equals(t.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "A RETIRED template cannot be test-sent");
        }
        String channel = t.get("channel").asString();
        Map<String, Object> sample = sampleVariables(t.get("variables_schema"));
        String subject = TemplateRenderer.render(text(t, "subject"), sample, false, false);
        String body = TemplateRenderer.render(t.get("body").asString(), sample, "EMAIL".equals(channel), false);
        ObjectNode values = json.createObjectNode();
        values.put("recipient_type", "EXTERNAL");
        values.put("channel", channel);
        values.put("destination", destination);
        values.put("notification_template_id", id.toString());
        values.put("category", t.get("category").asString());
        values.put("locale", t.get("locale").asString());
        String s = "[TEST] " + (subject == null ? t.get("code").asString() : subject);
        values.put("subject", s.length() > 255 ? s.substring(0, 255) : s);
        values.put("body", body);
        values.put("source_event_type", "notification_template.test_send");
        values.set("data", json.createObjectNode().put("test", true));
        return notifications.submit(values, null).row();
    }

    /** Sample values from a JSON-Schema-like {properties: {name: {example|examples|default}}}; else none. */
    private Map<String, Object> sampleVariables(JsonNode schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (schema == null || !schema.has("properties")) {
            return out;
        }
        JsonNode props = schema.get("properties");
        props.propertyNames().forEach(name -> {
            JsonNode p = props.get(name);
            JsonNode v = p.has("example") ? p.get("example")
                    : p.has("examples") && p.get("examples").isArray() && !p.get("examples").isEmpty() ? p.get("examples").get(0)
                    : p.get("default");
            if (v != null && v.isValueNode()) {
                out.put(name, v.asString());
            }
        });
        return out;
    }

    private void validateSyntax(ObjectNode values) {
        try {
            if (values.hasNonNull("subject")) {
                TemplateRenderer.variables(values.get("subject").asString());
            }
            if (values.hasNonNull("body")) {
                TemplateRenderer.variables(values.get("body").asString());
            }
        } catch (TemplateRenderer.InvalidTemplateException e) {
            throw ApiException.unprocessable("invalid_template", e.getMessage());
        }
        if (values.hasNonNull("locale") && !values.get("locale").asString().matches("[a-z]{2,3}([-_][A-Za-z0-9]{2,8})?")) {
            throw ApiException.unprocessable("invalid_locale", "locale must look like en, fr or pt-BR");
        }
    }

    private JsonNode template(UUID id) {
        return json.readTree(store.json("notification_template", id)
                .orElseThrow(() -> ApiException.notFound("notification template", id)));
    }

    private static String text(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asString() : null;
    }

    static boolean validDestination(String channel, String destination) {
        return DeliveryPolicy.destinationProblem(channel, destination) == null;
    }
}
