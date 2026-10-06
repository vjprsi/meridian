package io.meridian.crm.application;

import static io.meridian.crm.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.api.generated.model.CopilotFeedback;
import io.meridian.crm.api.generated.model.CopilotSession;
import io.meridian.crm.domain.CopilotStub;
import io.meridian.crm.domain.Redactor;
import io.meridian.crm.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Servicing copilot. Every invocation is an immutable audit record: the redacted prompt and its SHA-256, the
 * grounding records retrieved, the output and its SHA-256, citations, model, token estimates, latency and guardrail
 * flags. Generation is the deterministic {@link CopilotStub}; no customer data leaves the service.
 */
@Service
public class CopilotService {
    static final String T = "copilot_session";
    private static final Set<String> NEEDS_SUBJECT = Set.of("DRAFT_REPLY", "SUGGEST_NEXT_ACTION");

    private final Rows rows;
    private final CrudEngine crud;
    private final Events events;
    private final KnowledgeService knowledge;

    public CopilotService(Rows rows, CrudEngine crud, Events events, KnowledgeService knowledge) {
        this.rows = rows;
        this.crud = crud;
        this.events = events;
        this.knowledge = knowledge;
    }

    @Transactional
    public ObjectNode create(Object body) {
        long started = System.nanoTime();
        ObjectNode v = crud.toColumns(T, body);
        String task = text(v, "task");
        UUID caseId = Json.uuid(v, "service_case_id");
        UUID complaintId = Json.uuid(v, "complaint_id");
        UUID customerId = Json.uuid(v, "customer_id");
        String prompt = text(v, "prompt");
        List<FieldError> errors = new ArrayList<>();
        ObjectNode serviceCase = null;
        ObjectNode complaint = null;
        if (caseId != null) {
            serviceCase = rows.find("service_case", caseId).orElse(null);
            if (serviceCase == null) {
                errors.add(new FieldError("service_case_id", "not_found", "Service case does not exist"));
            }
        }
        if (complaintId != null) {
            complaint = rows.find("complaint", complaintId).orElse(null);
            if (complaint == null) {
                errors.add(new FieldError("complaint_id", "not_found", "Complaint does not exist"));
            }
        }
        for (ObjectNode subject : java.util.Arrays.asList(serviceCase, complaint)) {
            if (subject != null) {
                UUID owner = Json.uuid(subject, "customer_id");
                if (customerId != null && !customerId.equals(owner)) {
                    errors.add(new FieldError("customer_id", "customer_mismatch", "The case or complaint belongs to another customer"));
                }
                customerId = owner;
            }
        }
        switch (task) {
            case "SUMMARISE_CUSTOMER" -> require(customerId != null, "customer_id", errors);
            case "SUMMARISE_CASE" -> require(caseId != null, "service_case_id", errors);
            case "DRAFT_COMPLAINT_RESPONSE" -> require(complaintId != null, "complaint_id", errors);
            case "ANSWER_QUESTION" -> require(prompt != null && !prompt.isBlank(), "prompt", errors);
            case "TRANSLATE" -> {
                require(prompt != null && !prompt.isBlank(), "prompt", errors);
                require(text(v, "language") != null, "language", errors);
            }
            case "CLASSIFY_CASE" -> require(caseId != null || (prompt != null && !prompt.isBlank()), "service_case_id", errors);
            default -> {
                if (NEEDS_SUBJECT.contains(task)) {
                    require(caseId != null || complaintId != null, "service_case_id", errors);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_copilot_request", "Copilot request is invalid", errors);
        }
        Redactor.Result redacted = Redactor.redact(prompt);
        CopilotStub.Context ctx = context(customerId, serviceCase, complaint, redacted.text());
        CopilotStub.Output out = CopilotStub.run(task, redacted.text(), text(v, "language"), ctx);

        ArrayNode refs = v.putArray("context_refs");
        addRefs(refs, "service_case", ctx.cases());
        addRefs(refs, "complaint", ctx.complaints());
        addRefs(refs, "case_comment", ctx.comments());
        addRefs(refs, "interaction", ctx.interactions());
        addRefs(refs, "customer_note", ctx.notes());
        addRefs(refs, "customer_timeline_event", ctx.timeline());
        addRefs(refs, "knowledge_article", ctx.articles());
        addRefs(refs, "complaint_redress", ctx.redress());
        ArrayNode citations = v.putArray("citations");
        out.citations().forEach(c -> {
            ObjectNode o = citations.addObject();
            o.put("type", c.type());
            o.put("id", c.id());
            o.put("title", c.title());
        });
        ArrayNode flags = v.putArray("guardrail_flags");
        redacted.redactions().forEach((k, n) -> flags.addObject().put("type", "PII_REDACTED").put("entity", k).put("count", n));
        redacted.flags().forEach(f -> flags.addObject().put("type", f));
        if ("TRANSLATE".equals(task)) {
            flags.addObject().put("type", "TRANSLATION_UNAVAILABLE");
        }
        if (customerId != null) {
            v.put("customer_id", customerId.toString());
        }
        if (prompt != null) {
            v.put("prompt", redacted.text());
            v.put("prompt_hash", Redactor.sha256(redacted.text()));
        }
        v.put("output", out.text());
        v.put("output_hash", Redactor.sha256(out.text()));
        v.put("model", CopilotStub.MODEL);
        v.put("model_gateway_request_id", "local-" + UUID.randomUUID());
        int contextChars = refs.toString().length() + (prompt == null ? 0 : prompt.length());
        v.put("input_tokens", Math.max(1, contextChars / 4));
        v.put("output_tokens", Math.max(1, out.text().length() / 4));
        v.put("pii_redacted", true);
        v.put("latency_ms", (int) Math.max(1, (System.nanoTime() - started) / 1_000_000));
        ObjectNode row = rows.insert(T, v);
        events.publish("servicing.copilot_session.created", T, row, CopilotSession.class);
        return row;
    }

    private static void require(boolean ok, String field, List<FieldError> errors) {
        if (!ok) {
            errors.add(new FieldError(field, "required", field + " is required for this task"));
        }
    }

    private CopilotStub.Context context(UUID customerId, ObjectNode serviceCase, ObjectNode complaint, String prompt) {
        List<JsonNode> cases = List.of();
        List<JsonNode> complaints = List.of();
        List<JsonNode> interactions = List.of();
        List<JsonNode> notes = List.of();
        List<JsonNode> timeline = List.of();
        if (customerId != null) {
            cases = list("select row_to_json(t)::text from service_case t where t.customer_id = ? order by t.id desc limit 10", customerId);
            complaints = list("select row_to_json(t)::text from complaint t where t.customer_id = ? order by t.id desc limit 5", customerId);
            interactions = list("""
                    select row_to_json(t)::text from interaction t where t.customer_id = ? order by t.started_at desc limit 5""",
                    customerId);
            notes = list("""
                    select row_to_json(t)::text from customer_note t where t.customer_id = ? and t.is_pinned
                    and (t.expires_at is null or t.expires_at > now()) order by t.id desc limit 5""", customerId);
            timeline = list("""
                    select row_to_json(t)::text from customer_timeline_event t where t.customer_id = ?
                    order by t.occurred_at desc limit 10""", customerId);
        }
        List<JsonNode> comments = serviceCase == null ? List.of() : list("""
                select row_to_json(t)::text from case_comment t where t.service_case_id = ? order by t.id desc limit 5""",
                UUID.fromString(text(serviceCase, "id")));
        List<JsonNode> redress = complaint == null ? List.of() : list("""
                select row_to_json(t)::text from complaint_redress t where t.complaint_id = ? order by t.id""",
                UUID.fromString(text(complaint, "id")));
        String grounding = prompt != null ? prompt
                : serviceCase != null ? text(serviceCase, "subject") + " " + (text(serviceCase, "description") == null ? "" : text(serviceCase, "description"))
                : complaint != null ? text(complaint, "summary") : null;
        List<JsonNode> articles = new ArrayList<>(knowledge.grounding(grounding,
                serviceCase == null ? null : text(serviceCase, "case_type"), 3));
        return new CopilotStub.Context(serviceCase, complaint, cases, complaints, comments, interactions, notes, timeline,
                articles, redress);
    }

    private List<JsonNode> list(String sql, Object... args) {
        return new ArrayList<>(rows.query(sql, args));
    }

    private static void addRefs(ArrayNode refs, String type, List<JsonNode> rows) {
        rows.forEach(r -> refs.addObject().put("type", type).put("id", r.get("id").asString()));
    }

    // ------------------------------------------------------------------ feedback

    @Transactional
    public ObjectNode feedback(UUID sessionId, JsonNode req) {
        rows.get(T, sessionId);
        String feedback = text(req, "feedback");
        String edited = text(req, "edited_output");
        if ("EDITED".equals(feedback) && (edited == null || edited.isBlank())) {
            throw new ApiException(422, "invalid_feedback", "Feedback is invalid",
                    List.of(new FieldError("edited_output", "required", "EDITED feedback must carry the final text")));
        }
        if (!"EDITED".equals(feedback) && edited != null) {
            throw new ApiException(422, "invalid_feedback", "Feedback is invalid",
                    List.of(new FieldError("edited_output", "not_applicable", "edited_output is only sent with EDITED")));
        }
        ObjectNode v = rows.object();
        v.put("copilot_session_id", sessionId.toString());
        v.put("feedback", feedback);
        if (edited != null) {
            v.put("edited_output", edited);
            v.put("edited_output_hash", Redactor.sha256(edited));
        }
        if (text(req, "comment") != null) {
            v.put("comment", Json.truncate(text(req, "comment"), 1000));
        }
        v.put("staff_user", Json.truncate(TenantContext.principal(), 64));
        ObjectNode row = rows.insert("copilot_feedback", v);
        events.publish("servicing.copilot_feedback.recorded", "copilot_feedback", row, CopilotFeedback.class);
        return row;
    }
}
