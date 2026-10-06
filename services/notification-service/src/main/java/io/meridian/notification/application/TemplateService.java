package io.meridian.notification.application;

import io.meridian.common.error.ApiException;
import io.meridian.notification.domain.DeliveryPolicy;
import io.meridian.notification.domain.TemplateRenderer;
import io.meridian.notification.infrastructure.NotificationStore;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.crud.CrudEngine;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Template lifecycle: DRAFT (editable) -> PENDING_APPROVAL (publish, via workflow-service maker-checker) -> ACTIVE
 * (approved; the previously active version of the same code/channel/locale is RETIRED) or back to DRAFT (rejected).
 * A new version of an existing template is created by POSTing the same code/channel/locale again.
 */
@Service
public class TemplateService {
    private static final Logger log = LoggerFactory.getLogger(TemplateService.class);
    public static final String RESOURCE_TYPE = "notification_template";

    private final NotificationStore store;
    private final CrudEngine crud;
    private final ServiceClient services;
    private final NotificationService notifications;
    private final JsonMapper json;

    public TemplateService(NotificationStore store, CrudEngine crud, ServiceClient services,
                           NotificationService notifications, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.services = services;
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

    /** Checks the update is allowed (DRAFT only, no status change) and returns the cleaned body. */
    @Transactional(readOnly = true)
    public ObjectNode prepareUpdate(UUID id, Object body) {
        JsonNode current = json.readTree(store.json("notification_template", id)
                .orElseThrow(() -> ApiException.notFound("notification template", id)));
        ObjectNode values = crud.toColumns("notification_template", body);
        if (values.hasNonNull("status") && !values.get("status").asString().equals(current.get("status").asString())) {
            throw ApiException.conflict("invalid_transition",
                    "Template status changes only through /publish and approval; PATCH cannot set status");
        }
        values.remove("status");
        values.remove("version");
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
        ObjectNode request = json.createObjectNode();
        request.put("resource_type", RESOURCE_TYPE);
        request.put("resource_id", id.toString());
        request.put("action", "publish");
        request.put("title", "Publish notification template " + t.get("code").asString() + " v" + t.get("version").asInt()
                + " (" + t.get("channel").asString() + "/" + t.get("locale").asString() + ")");
        request.put("summary", "Activate version " + t.get("version").asInt() + " of " + t.get("code").asString()
                + "; the currently active version will be retired.");
        request.set("payload", payload);
        String key = ("ntp-publish:" + id + ":" + idempotencyKey);
        return services.post("workflow-service", "/v1/approval-requests", request,
                key.substring(0, Math.min(128, key.length())));
    }

    /**
     * Applies a maker-checker outcome for a template (from platform.approval_request.* events). Approved or executed
     * activates the template and retires the previous active version; rejected, cancelled or expired returns it to
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
            case "REJECTED", "CANCELLED", "EXPIRED" -> store.setTemplateStatus(templateId, "DRAFT");
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
