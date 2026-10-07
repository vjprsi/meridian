package io.meridian.crm.application;

import static io.meridian.crm.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.crm.domain.Templates;
import io.meridian.crm.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Approved reply templates. Rendering fills {@code {{placeholders}}} from the case ({@code case.*}), the complaint
 * ({@code complaint.*}), the customer id and caller-supplied {@code values} (which win), and reports the
 * placeholders it could not resolve so the agent never sends a half-filled reply unknowingly.
 */
@Service
public class CannedResponseService {
    static final String T = "canned_response";
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");
    private static final List<String> CASE_FIELDS = List.of("case_number", "subject", "status", "priority", "case_type",
            "channel", "resolution_summary", "resolution_code", "resolution_due_at", "first_response_due_at");
    private static final List<String> COMPLAINT_FIELDS = List.of("complaint_number", "summary", "status", "category",
            "received_at", "acknowledgement_due_at", "final_response_due_at", "outcome", "root_cause", "resolution_summary",
            "redress_amount", "redress_currency", "escalated_to");

    private final Rows rows;
    private final CrudEngine crud;

    public CannedResponseService(Rows rows, CrudEngine crud) {
        this.rows = rows;
        this.crud = crud;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        validate(v);
        return rows.insert(T, v);
    }

    @Transactional
    public ObjectNode update(UUID id, String ifMatch, Object body) {
        int expected = Json.parseVersion(ifMatch);
        ObjectNode current = rows.lock(T, id);
        ObjectNode v = crud.toColumns(T, body);
        ObjectNode merged = current.deepCopy();
        merged.setAll(v);
        validate(merged);
        return rows.update(T, id, v, expected).orElseThrow(() -> Json.versionConflict(expected));
    }

    private static void validate(ObjectNode v) {
        List<FieldError> errors = new java.util.ArrayList<>();
        if (text(v, "code") == null || !CODE.matcher(text(v, "code")).matches()) {
            errors.add(new FieldError("code", "invalid_code", "code must be upper-case letters, digits and '_'"));
        }
        Templates.validate(text(v, "body")).forEach(p -> errors.add(new FieldError("body", "invalid_template", p)));
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_template", "Canned response is invalid", errors);
        }
    }

    @Transactional(readOnly = true)
    public ObjectNode render(UUID id, JsonNode req) {
        ObjectNode template = rows.get(T, id);
        if (!Json.bool(template, "is_active")) {
            throw ApiException.unprocessable("template_inactive", "Canned response " + text(template, "code") + " is inactive");
        }
        Map<String, String> values = new HashMap<>();
        UUID customerId = Json.uuid(req, "customer_id");
        UUID caseId = Json.uuid(req, "service_case_id");
        UUID complaintId = Json.uuid(req, "complaint_id");
        if (caseId != null) {
            ObjectNode c = rows.find("service_case", caseId).orElseThrow(() ->
                    ApiException.unprocessable("service_case_not_found", "Service case " + caseId + " does not exist"));
            customerId = sameCustomer(customerId, c);
            put(values, "case", c, CASE_FIELDS);
        }
        if (complaintId != null) {
            ObjectNode c = rows.find("complaint", complaintId).orElseThrow(() ->
                    ApiException.unprocessable("complaint_not_found", "Complaint " + complaintId + " does not exist"));
            customerId = sameCustomer(customerId, c);
            put(values, "complaint", c, COMPLAINT_FIELDS);
            values.put("ombudsman", io.meridian.crm.domain.RegulatoryTimelines.ombudsman(text(c, "country")));
        }
        if (customerId != null) {
            values.put("customer.id", customerId.toString());
        }
        values.put("today", rows.today().toString());
        JsonNode extra = req.get("values");
        if (extra != null && extra.isObject()) {
            extra.propertyNames().forEach(k -> {
                JsonNode x = extra.get(k);
                if (x != null && !x.isNull()) {
                    values.put(k, x.isString() ? x.asString() : x.toString());
                }
            });
        }
        Templates.Rendered r = Templates.render(text(template, "body"), values);
        ObjectNode out = rows.object();
        out.put("canned_response_id", id.toString());
        out.put("code", text(template, "code"));
        out.put("language", text(template, "language"));
        out.set("channel", template.get("channel"));
        out.put("rendered", r.text());
        ArrayNode used = out.putArray("placeholders_used");
        r.used().forEach(used::add);
        ArrayNode unresolved = out.putArray("unresolved_placeholders");
        r.unresolved().forEach(unresolved::add);
        out.put("complete", r.unresolved().isEmpty());
        return out;
    }

    private static UUID sameCustomer(UUID customerId, ObjectNode subject) {
        UUID owner = Json.uuid(subject, "customer_id");
        if (customerId != null && !customerId.equals(owner)) {
            throw ApiException.unprocessable("customer_mismatch", "The case or complaint belongs to another customer");
        }
        return owner;
    }

    private static void put(Map<String, String> values, String prefix, ObjectNode row, List<String> fields) {
        for (String f : fields) {
            String v = text(row, f);
            if (v != null) {
                if (f.endsWith("_at")) {
                    v = OffsetDateTime.parse(v).toLocalDate().toString();
                }
                values.put(prefix + "." + f, v);
                values.putIfAbsent(f, v);
            }
        }
    }
}
