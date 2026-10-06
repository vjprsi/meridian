package io.meridian.document.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.document.domain.Lifecycles;
import io.meridian.document.domain.TemplateRenderer;
import io.meridian.document.infrastructure.DocumentStore;
import io.meridian.events.OutboxWriter;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.crud.CrudEngine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Document templates: versioned per code + locale, editable only as DRAFT, published through a maker-checker
 * approval in workflow-service, activated when that approval is granted (superseding the previous ACTIVE
 * version), and retired explicitly.
 *
 * <p>The publish approval goes through libs/meridian-approvals (G11). There is no local decision endpoint, so
 * publishing always opens the request in workflow-service whatever {@code meridian.approvals.mode} says; the
 * decision comes back through {@link DocumentTemplateApprovalHandler}.
 */
@Service
public class TemplateService {
    /** Approval resource type for template publishing in workflow-service. */
    public static final String APPROVAL_RESOURCE = "document_template";

    private final CrudEngine crud;
    private final DocumentStore store;
    private final Approvals approvals;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final OutboxWriter outbox;

    public TemplateService(CrudEngine crud, DocumentStore store, ServiceClient services,
                           @Value("${spring.application.name}") String application, JdbcClient jdbc, JsonMapper json,
                           OutboxWriter outbox) {
        this.outbox = outbox;
        this.crud = crud;
        this.store = store;
        this.approvals = new Approvals(services, application, "workflow");
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode values = crud.toColumns("document_template", body);
        if (!values.hasNonNull("body") && !values.hasNonNull("body_document_id")) {
            throw ApiException.unprocessable("template_body_required", "Either body or body_document_id is required");
        }
        validateBody(values);
        String locale = values.hasNonNull("locale") ? values.get("locale").asString() : "en";
        String code = values.get("code").asString();
        jdbc.sql("select 1 from (select pg_advisory_xact_lock(hashtext(?))) l")
                .param("document_template:" + code + ":" + locale).query(Integer.class).single();
        int version = jdbc.sql("select coalesce(max(version), 0) + 1 from document_template where code = ? and locale = ?")
                .params(code, locale).query(Integer.class).single();
        values.put("locale", locale);
        values.put("version", version);
        values.put("status", "DRAFT");
        return crud.insert("document_template", values);
    }

    @Transactional(readOnly = true)
    public void validateUpdate(UUID id, Object body) {
        JsonNode current = json.readTree(store.row("document_template", id));
        ObjectNode changes = crud.toColumns("document_template", body);
        if (!"DRAFT".equals(current.get("status").asString())) {
            throw ApiException.conflict("template_not_editable",
                    "Only DRAFT templates can be edited; create a new version instead (status is "
                            + current.get("status").asString() + ")");
        }
        for (String f : List.of("code", "locale")) {
            if (changes.has(f) && !changes.get(f).asString().equals(current.get(f).asString())) {
                throw ApiException.unprocessable("immutable_field", f + " cannot be changed; create a new template instead");
            }
        }
        validateBody(changes);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preview(UUID id, JsonNode data) {
        JsonNode t = json.readTree(store.row("document_template", id));
        if (!t.hasNonNull("body")) {
            throw ApiException.unprocessable("template_not_renderable",
                    "Template " + id + " has a binary body document; only text templates can be previewed");
        }
        Rendered r = render(t, data);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document_template_id", id.toString());
        out.put("code", t.get("code").asString());
        out.put("version", t.get("version").asInt());
        out.put("output_format", t.get("output_format").asString());
        out.put("content_type", r.contentType());
        out.put("rendered", r.content());
        out.put("missing_variables", r.missing());
        return out;
    }

    /** DRAFT -> PENDING_APPROVAL and an approval request in workflow-service (four-eyes). */
    @Transactional
    public JsonNode publish(UUID id, String idempotencyKey) {
        JsonNode t = store.lock("document_template", id);
        transition(id, t.get("status").asString(), "PENDING_APPROVAL");
        ObjectNode payload = json.createObjectNode();
        payload.put("service", "document-service");
        payload.put("document_template_id", id.toString());
        payload.put("code", t.get("code").asString());
        payload.put("locale", t.get("locale").asString());
        payload.put("version", t.get("version").asInt());
        payload.put("checksum", checksum(t));
        return approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "publish",
                "Publish document template " + t.get("code").asString() + " v" + t.get("version").asInt()
                        + " (" + t.get("locale").asString() + ")", payload)
                .withSummary(t.get("name").asString()), "publish:" + id + ":" + idempotencyKey);
    }

    /**
     * A decision from workflow-service on a publish request: ignored unless the template is still PENDING_APPROVAL
     * with the submitted content (checksum); an approval by the template's maker or publisher is refused
     * ({@code four_eyes_violation}).
     *
     * @return true when the template changed
     */
    @Transactional
    public boolean onWorkflowDecision(UUID templateId, String checksum, String approvalStatus, String maker) {
        JsonNode t;
        try {
            t = store.lock("document_template", templateId);
        } catch (ApiException e) {
            return false;
        }
        if (!"PENDING_APPROVAL".equals(t.get("status").asString())
                || (checksum != null && !checksum.equals(checksum(t)))) {
            return false;
        }
        String principal = TenantContext.principal();
        if (("APPROVED".equals(approvalStatus) || "EXECUTED".equals(approvalStatus))
                && (principal.equals(maker) || principal.equals(t.path("created_by").asString(null))
                        || principal.equals(t.path("updated_by").asString(null)))) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The maker of a template cannot approve its publication; another user must act as checker");
        }
        return onApprovalDecided(templateId, approvalStatus);
    }

    /** Content fingerprint of a template version (what approvers saw). */
    static String checksum(JsonNode t) {
        String content = t.path("body").asString("") + "\u0000" + t.path("body_document_id").asString("") + "\u0000"
                + t.path("variables_schema").toString() + "\u0000" + t.path("output_format").asString("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Outcome of the publish approval (from {@code platform.approval_request.*} events). Approved activates the
     * version and retires the previously active one; rejected / expired / cancelled / changes requested returns it
     * to DRAFT.
     *
     * @return true when the template changed
     */
    @Transactional
    public boolean onApprovalDecided(UUID templateId, String approvalStatus) {
        JsonNode t;
        try {
            t = store.lock("document_template", templateId);
        } catch (ApiException e) {
            return false;
        }
        if (!"PENDING_APPROVAL".equals(t.get("status").asString())) {
            return false;
        }
        switch (approvalStatus) {
            case "APPROVED", "EXECUTED" -> {
                jdbc.sql("""
                        update document_template set status = 'RETIRED', updated_by = ?
                        where code = ? and locale = ? and status = 'ACTIVE' and id <> ?""")
                        .params(TenantContext.principal(), t.get("code").asString(), t.get("locale").asString(), templateId)
                        .update();
                setStatus(templateId, "ACTIVE");
                outbox.publish("platform.document_template.activated", "document_template", templateId,
                        json.readTree(store.row("document_template", templateId)));
                return true;
            }
            case "REJECTED", "EXPIRED", "CANCELLED", "CHANGES_REQUESTED" -> {
                setStatus(templateId, "DRAFT");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Transactional
    public String retire(UUID id) {
        JsonNode t = store.lock("document_template", id);
        transition(id, t.get("status").asString(), "RETIRED");
        return store.row("document_template", id);
    }

    // ------------------------------------------------------------------ rendering

    public record Rendered(String content, String contentType, List<String> missing) {}

    /** Renders a template row with data; missing covers both unresolved tags and schema-required variables. */
    public Rendered render(JsonNode template, JsonNode data) {
        String format = template.get("output_format").asString();
        boolean markup = "HTML".equals(format) || "PDF".equals(format);
        @SuppressWarnings("unchecked")
        Map<String, Object> vars = data == null || !data.isObject() ? Map.of() : json.convertValue(data, Map.class);
        TemplateRenderer.Result r;
        try {
            r = TemplateRenderer.render(template.get("body").asString(), vars, markup);
        } catch (TemplateRenderer.SyntaxError e) {
            throw ApiException.unprocessable("invalid_template", e.getMessage());
        }
        List<String> missing = new ArrayList<>(r.missing());
        JsonNode required = template.path("variables_schema").path("required");
        if (required.isArray()) {
            for (JsonNode n : required) {
                String name = n.asString();
                if (TemplateRenderer.render("{{" + name + "}}", vars, false).missing().size() > 0 && !missing.contains(name)) {
                    missing.add(name);
                }
            }
        }
        String contentType = switch (format) {
            case "TEXT" -> "text/plain";
            case "CSV" -> "text/csv";
            default -> "text/html";
        };
        return new Rendered(r.output(), contentType, missing);
    }

    private void validateBody(ObjectNode values) {
        if (values.hasNonNull("body")) {
            try {
                TemplateRenderer.validate(values.get("body").asString());
            } catch (TemplateRenderer.SyntaxError e) {
                throw ApiException.unprocessable("invalid_template", e.getMessage());
            }
        }
    }

    private void transition(UUID id, String from, String to) {
        try {
            Lifecycles.require(Lifecycles.TEMPLATE, "Template", from, to);
        } catch (Lifecycles.InvalidTransition e) {
            throw ApiException.conflict(e.code(), e.getMessage());
        }
        setStatus(id, to);
    }

    private void setStatus(UUID id, String status) {
        jdbc.sql("update document_template set status = ?, updated_by = ? where id = ?")
                .params(status, TenantContext.principal(), id).update();
    }
}
