package io.meridian.casemanagement.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.casemanagement.domain.CaseRules;
import io.meridian.casemanagement.domain.NarrativeBuilder;
import io.meridian.casemanagement.infrastructure.Db;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Investigation cases: opening (numbered), assignment, escalation, alert linking, notes, tasks, evidence, the
 * maker-checker disposition that closes a case, reopening, the merged timeline and the case summary.
 */
@Service
public class CaseService {
    /** Approval resource type for proposed case dispositions in workflow-service (G11). */
    public static final String DISPOSITION_APPROVAL = "aml_case_disposition";

    private final Db db;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final Approvals approvals;

    public CaseService(Db db, CrudEngine crud, OutboxWriter outbox, JsonMapper json, Approvals approvals) {
        this.approvals = approvals;
        this.db = db;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
    }

    // ------------------------------------------------------------------ cases

    @Transactional
    public String open(JsonNode body) {
        ObjectNode v = crud.toColumns("aml_case", body);
        String type = Db.str(v, "case_type");
        String priority = v.hasNonNull("priority") ? v.get("priority").asString() : "MEDIUM";
        if (v.hasNonNull("total_amount") && !v.hasNonNull("currency")) {
            throw ApiException.unprocessable("currency_required", "currency is required with total_amount");
        }
        long seq = db.jdbc().sql("select nextval('aml_case_number_seq')").query(Long.class).single();
        v.put("case_number", "%s-%d-%06d".formatted(CaseRules.prefix(type), LocalDate.now(ZoneOffset.UTC).getYear(), seq));
        v.put("status", "OPEN");
        if (!v.hasNonNull("due_at")) {
            v.put("due_at", io.meridian.common.time.Clocks.now().plusDays(CaseRules.dueDays(priority)).toString());
        }
        String row = crud.insert("aml_case", v);
        JsonNode n = json.readTree(row);
        outbox.publish("risk.aml_case.opened", "aml_case", id(n), n);
        return row;
    }

    @Transactional
    public String update(UUID id, JsonNode changes, int expectedVersion) {
        JsonNode c = db.require("aml_case", id, true);
        require(c, "edit");
        if (c.get("row_version").asInt() != expectedVersion) {
            throw ApiException.conflict("version_conflict", "If-Match " + expectedVersion + " does not match the current version; reload and retry");
        }
        ObjectNode v = crud.toColumns("aml_case", changes);
        for (String f : List.of("status", "case_number", "escalated_to", "closed_at", "opened_at", "ai_summary")) {
            v.remove(f);
        }
        return crud.updateRow("aml_case", Map.of("id", id), v, expectedVersion).orElseThrow();
    }

    @Transactional
    public String assign(UUID id, String assignee, String team) {
        JsonNode c = db.require("aml_case", id, true);
        require(c, "assign");
        db.update("update aml_case set assigned_to = ?, team = coalesce(?, team), status = ?, updated_by = ? where id = ?",
                assignee, team, CaseRules.afterAssign(c.get("status").asString()), TenantContext.principal(), id);
        return publish(id, "risk.aml_case.assigned");
    }

    @Transactional
    public String escalate(UUID id, String to, String reason) {
        JsonNode c = db.require("aml_case", id, true);
        require(c, "escalate");
        db.update("""
                update aml_case set status = 'ESCALATED', escalated_to = ?, escalation_reason = ?,
                    priority = case when priority in ('LOW', 'MEDIUM') then 'HIGH' else priority end, updated_by = ?
                where id = ?""", to, reason, TenantContext.principal(), id);
        note(id, "Escalated to " + to + ": " + reason, "INTERNAL");
        return publish(id, "risk.aml_case.escalated");
    }

    @Transactional
    public String reopen(UUID id, String reason) {
        JsonNode c = db.require("aml_case", id, true);
        require(c, "reopen");
        db.update("update aml_case set status = 'INVESTIGATING', closed_at = null, updated_by = ? where id = ?",
                TenantContext.principal(), id);
        note(id, "Case reopened: " + reason, "INTERNAL");
        return publish(id, "risk.aml_case.reopened");
    }

    /** Links an alert or referral; linking the same alert again returns the existing link. */
    @Transactional
    public String linkAlert(UUID caseId, JsonNode body) {
        JsonNode c = db.require("aml_case", caseId, true);
        require(c, "link_alert");
        String source = Db.str(body, "source");
        UUID alertId = Db.uuid(body, "alert_id");
        if (alertId == null && ("FRAUD_ALERT".equals(source) || "TM_ALERT".equals(source) || "SCREENING_RESULT".equals(source))) {
            throw ApiException.unprocessable("alert_id_required", source + " links need the alert_id");
        }
        if (alertId != null) {
            var existing = db.one("select row_to_json(a)::text from aml_case_alert a where aml_case_id = ? and source = ? and alert_id = ?",
                    caseId, source, alertId);
            if (existing.isPresent()) {
                return db.text(existing.get());
            }
        }
        ObjectNode v = crud.toColumns("aml_case_alert", body);
        v.put("aml_case_id", caseId.toString());
        String row = crud.insert("aml_case_alert", v);
        JsonNode n = json.readTree(row);
        outbox.publish("risk.aml_case.alert_linked", "aml_case_alert", id(n), n);
        return row;
    }

    @Transactional
    public String addNote(UUID caseId, String body, String visibility, JsonNode attachments) {
        db.require("aml_case", caseId, false);
        String vis = visibility == null ? "INTERNAL" : visibility;
        if (!CaseRules.NOTE_VISIBILITY.contains(vis)) {
            throw ApiException.unprocessable("invalid_visibility", "visibility must be INTERNAL or RESTRICTED");
        }
        if (body == null || body.isBlank()) {
            throw ApiException.unprocessable("body_required", "body is required");
        }
        ObjectNode v = json.createObjectNode();
        v.put("aml_case_id", caseId.toString());
        v.put("author", TenantContext.principal());
        v.put("body", body);
        v.put("visibility", vis);
        if (attachments != null && attachments.isArray()) {
            v.set("attachments", attachments);
        }
        return crud.insert("aml_case_note", v);
    }

    private void note(UUID caseId, String text, String visibility) {
        ObjectNode v = json.createObjectNode();
        v.put("aml_case_id", caseId.toString());
        v.put("author", TenantContext.principal());
        v.put("body", text);
        v.put("visibility", visibility);
        crud.insert("aml_case_note", v);
    }

    // ------------------------------------------------------------------ tasks and evidence

    @Transactional
    public String createTask(JsonNode body) {
        UUID caseId = Db.uuid(body, "aml_case_id");
        require(db.require("aml_case", caseId, false), "work");
        ObjectNode v = crud.toColumns("aml_case_task", body);
        String status = v.hasNonNull("status") ? v.get("status").asString() : "OPEN";
        if (!"OPEN".equals(status) && !"IN_PROGRESS".equals(status)) {
            throw ApiException.unprocessable("invalid_status", "A task starts OPEN or IN_PROGRESS");
        }
        return crud.insert("aml_case_task", v);
    }

    @Transactional
    public String updateTask(UUID id, JsonNode changes, int expectedVersion) {
        JsonNode t = db.require("aml_case_task", id, true);
        if (t.get("row_version").asInt() != expectedVersion) {
            throw ApiException.conflict("version_conflict", "If-Match " + expectedVersion + " does not match the current version; reload and retry");
        }
        String from = t.get("status").asString();
        if ("DONE".equals(from) || "CANCELLED".equals(from)) {
            throw ApiException.conflict("task_closed", "Task is " + from);
        }
        ObjectNode v = crud.toColumns("aml_case_task", changes);
        v.remove("aml_case_id");
        if (v.hasNonNull("status")) {
            String to = v.get("status").asString();
            if ("DONE".equals(to)) {
                throw ApiException.unprocessable("use_complete", "Use /complete to finish a task");
            }
            if (!CaseRules.taskTransition(from, to)) {
                throw ApiException.conflict("invalid_transition", "Task cannot go from " + from + " to " + to);
            }
        }
        return crud.updateRow("aml_case_task", Map.of("id", id), v, expectedVersion).orElseThrow();
    }

    @Transactional
    public String completeTask(UUID id, String note) {
        JsonNode t = db.require("aml_case_task", id, true);
        String from = t.get("status").asString();
        if (!"OPEN".equals(from) && !"IN_PROGRESS".equals(from)) {
            throw ApiException.conflict("invalid_transition", "Cannot complete a task that is " + from);
        }
        db.update("update aml_case_task set status = 'DONE', completed_at = now(), completed_by = ?, updated_by = ? where id = ?",
                TenantContext.principal(), TenantContext.principal(), id);
        if (note != null && !note.isBlank()) {
            note(UUID.fromString(t.get("aml_case_id").asString()), "Task '" + t.get("title").asString() + "' completed: " + note, "INTERNAL");
        }
        return db.text(db.require("aml_case_task", id, false));
    }

    @Transactional
    public String addEvidence(JsonNode body) {
        UUID caseId = Db.uuid(body, "aml_case_id");
        require(db.require("aml_case", caseId, false), "work");
        ObjectNode v = crud.toColumns("aml_case_evidence", body);
        if (!CaseRules.EVIDENCE_TYPES.contains(Db.str(v, "evidence_type"))) {
            throw ApiException.unprocessable("invalid_evidence_type", "evidence_type must be one of " + CaseRules.EVIDENCE_TYPES);
        }
        String sha = Db.str(v, "sha256");
        if (sha == null || !sha.matches("[0-9a-fA-F]{64}")) {
            throw ApiException.unprocessable("invalid_sha256", "sha256 must be 64 hex characters");
        }
        v.put("sha256", sha.toLowerCase(java.util.Locale.ROOT));
        return crud.insert("aml_case_evidence", v);
    }

    // ------------------------------------------------------------------ disposition (maker-checker)

    /** Proposes the disposition; the case waits for a second user's approval. */
    @Transactional
    public String proposeDisposition(UUID caseId, String disposition, String rationale, JsonNode actions) {
        JsonNode c = db.require("aml_case", caseId, true);
        require(c, "close");
        int open = db.jdbc().sql("select count(*) from aml_case_task where aml_case_id = ? and status in ('OPEN', 'IN_PROGRESS')")
                .param(caseId).query(Integer.class).single();
        if (open > 0) {
            throw ApiException.conflict("open_tasks", open + " task(s) are still open; complete or cancel them first");
        }
        if ("SAR_FILED".equals(disposition) && db.one("""
                select 1::text from sar_report where aml_case_id = ? and status in ('SUBMITTED', 'ACKNOWLEDGED')""", caseId).isEmpty()) {
            throw ApiException.unprocessable("sar_not_filed", "A SAR_FILED disposition needs a filed SAR/STR for the case");
        }
        ObjectNode v = json.createObjectNode();
        v.put("aml_case_id", caseId.toString());
        v.put("disposition", disposition);
        v.put("rationale", rationale);
        v.put("decided_by", TenantContext.principal());
        v.put("decided_at", io.meridian.common.time.Clocks.now().toString());
        v.put("approval_status", "PENDING_APPROVAL");
        if (actions != null && actions.isArray()) {
            v.set("actions_taken", actions);
        }
        String row = crud.insert("aml_case_disposition", v);
        db.update("update aml_case set status = 'PENDING_APPROVAL', updated_by = ? where id = ?", TenantContext.principal(), caseId);
        JsonNode n = json.readTree(row);
        outbox.publish("risk.aml_case_disposition.recorded", "aml_case_disposition", id(n), n);
        approvals.request(dispositionApproval(c, n), "disposition:" + id(n));
        return row;
    }

    /**
     * Central maker-checker: the MLRO decides in the workflow inbox; DispositionApprovalHandler applies the decision.
     * Tipping-off: the payload carries ids and the case header only; the rationale and case content stay here.
     */
    private ApprovalSpec dispositionApproval(JsonNode c, JsonNode d) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("aml_case_disposition_id", d.get("id").asString());
        payload.put("aml_case_id", c.get("id").asString());
        payload.put("case_number", c.get("case_number").asString());
        payload.put("case_type", c.get("case_type").asString());
        payload.put("case_priority", Db.str(c, "priority"));
        payload.put("disposition", d.get("disposition").asString());
        payload.put("decided_by", d.get("decided_by").asString());
        payload.put("actions_taken", d.path("actions_taken").size());
        ApprovalSpec spec = ApprovalSpec.of(DISPOSITION_APPROVAL, id(d), "approve",
                        "Case " + c.get("case_number").asString() + " disposition " + d.get("disposition").asString(), payload)
                .withSummary("Approve the proposed disposition to close case " + c.get("case_number").asString()
                        + " (details in case management)");
        BigDecimal amount = Db.dec(c, "total_amount");
        return amount != null && Db.str(c, "currency") != null ? spec.withAmount(amount, Db.str(c, "currency")) : spec;
    }

    /** True while the disposition is still PENDING_APPROVAL (each proposal is its own row). */
    @Transactional(readOnly = true)
    public boolean dispositionAwaitsApproval(UUID id) {
        return db.one("select row_to_json(d)::text from aml_case_disposition d where id = ?", id)
                .filter(d -> "PENDING_APPROVAL".equals(Db.str(d, "approval_status")))
                .isPresent();
    }

    @Transactional
    public String approveDisposition(UUID id, String comment) {
        JsonNode d = pendingDisposition(id);
        if (TenantContext.principal().equals(d.get("decided_by").asString())) {
            throw ApiException.unprocessable("four_eyes_violation", "The investigator who proposed the disposition cannot approve it");
        }
        UUID caseId = UUID.fromString(d.get("aml_case_id").asString());
        db.update("""
                update aml_case_disposition set approval_status = 'APPROVED', approved_by = ?, approved_at = now(),
                    approval_comment = ?, updated_by = ? where id = ?""",
                TenantContext.principal(), comment, TenantContext.principal(), id);
        db.update("update aml_case set status = 'CLOSED', closed_at = now(), updated_by = ? where id = ?", TenantContext.principal(), caseId);
        JsonNode row = db.require("aml_case_disposition", id, false);
        outbox.publish("risk.aml_case_disposition.approved", "aml_case_disposition", id, row);
        publish(caseId, "risk.aml_case.closed");
        approvals.cancelOpen(DISPOSITION_APPROVAL, id, null, "approved directly");
        return db.text(row);
    }

    @Transactional
    public String rejectDisposition(UUID id, String reason) {
        JsonNode d = pendingDisposition(id);
        UUID caseId = UUID.fromString(d.get("aml_case_id").asString());
        db.update("""
                update aml_case_disposition set approval_status = 'REJECTED', approved_by = ?, approved_at = now(),
                    approval_comment = ?, updated_by = ? where id = ?""",
                TenantContext.principal(), reason, TenantContext.principal(), id);
        db.update("update aml_case set status = 'INVESTIGATING', updated_by = ? where id = ?", TenantContext.principal(), caseId);
        note(caseId, "Disposition " + d.get("disposition").asString() + " rejected: " + reason, "INTERNAL");
        JsonNode row = db.require("aml_case_disposition", id, false);
        outbox.publish("risk.aml_case_disposition.rejected", "aml_case_disposition", id, row);
        approvals.cancelOpen(DISPOSITION_APPROVAL, id, null, "rejected directly");
        return db.text(row);
    }

    private JsonNode pendingDisposition(UUID id) {
        JsonNode d = db.require("aml_case_disposition", id, true);
        if (!"PENDING_APPROVAL".equals(d.get("approval_status").asString())) {
            throw ApiException.conflict("invalid_transition", "Disposition is already " + d.get("approval_status").asString());
        }
        db.require("aml_case", UUID.fromString(d.get("aml_case_id").asString()), true);
        return d;
    }

    // ------------------------------------------------------------------ timeline and summary

    public Map<String, Object> timeline(UUID caseId, boolean includeRestricted) {
        JsonNode c = db.require("aml_case", caseId, false);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item(c.get("opened_at").asString(), "CASE_OPENED", "aml_case:" + caseId,
                "Case " + c.get("case_number").asString() + " opened: " + c.get("title").asString(), Db.str(c, "created_by")));
        for (JsonNode a : db.list("select row_to_json(a)::text from aml_case_alert a where aml_case_id = ?", caseId)) {
            items.add(item(a.get("created_at").asString(), "ALERT_LINKED", "aml_case_alert:" + a.get("id").asString(),
                    a.get("source").asString() + (a.hasNonNull("summary") ? ": " + a.get("summary").asString() : ""), Db.str(a, "created_by")));
        }
        for (JsonNode n : db.list("select row_to_json(n)::text from aml_case_note n where aml_case_id = ?", caseId)) {
            boolean restricted = "RESTRICTED".equals(n.get("visibility").asString());
            items.add(item(n.get("created_at").asString(), "NOTE", "aml_case_note:" + n.get("id").asString(),
                    restricted && !includeRestricted ? "Restricted note (tipping-off protection)" : n.get("body").asString(),
                    n.get("author").asString()));
        }
        for (JsonNode t : db.list("select row_to_json(t)::text from aml_case_task t where aml_case_id = ?", caseId)) {
            items.add(item(t.get("created_at").asString(), "TASK_CREATED", "aml_case_task:" + t.get("id").asString(),
                    t.get("title").asString(), Db.str(t, "created_by")));
            if (t.hasNonNull("completed_at")) {
                items.add(item(t.get("completed_at").asString(), "TASK_COMPLETED", "aml_case_task:" + t.get("id").asString(),
                        t.get("title").asString(), Db.str(t, "completed_by")));
            }
        }
        for (JsonNode e : db.list("select row_to_json(e)::text from aml_case_evidence e where aml_case_id = ?", caseId)) {
            items.add(item(e.get("created_at").asString(), "EVIDENCE_ADDED", "aml_case_evidence:" + e.get("id").asString(),
                    e.get("evidence_type").asString() + (e.hasNonNull("description") ? ": " + e.get("description").asString() : ""),
                    Db.str(e, "created_by")));
        }
        for (JsonNode d : db.list("select row_to_json(d)::text from aml_case_disposition d where aml_case_id = ?", caseId)) {
            items.add(item(d.get("decided_at").asString(), "DISPOSITION_PROPOSED", "aml_case_disposition:" + d.get("id").asString(),
                    d.get("disposition").asString() + ": " + d.get("rationale").asString(), d.get("decided_by").asString()));
            if (d.hasNonNull("approved_at")) {
                items.add(item(d.get("approved_at").asString(), "DISPOSITION_" + d.get("approval_status").asString(),
                        "aml_case_disposition:" + d.get("id").asString(), Db.str(d, "approval_comment"), Db.str(d, "approved_by")));
            }
        }
        for (JsonNode s : db.list("select row_to_json(s)::text from sar_report s where aml_case_id = ?", caseId)) {
            String ref = "sar_report:" + s.get("id").asString();
            items.add(item(s.get("created_at").asString(), "SAR_CREATED", ref, s.get("report_type").asString() + " draft", Db.str(s, "created_by")));
            if (s.hasNonNull("submitted_at")) {
                items.add(item(s.get("submitted_at").asString(), "SAR_FILED", ref, "Filed with " + s.get("regulator").asString(), Db.str(s, "updated_by")));
            }
            if (s.hasNonNull("acknowledged_at")) {
                items.add(item(s.get("acknowledged_at").asString(), "SAR_ACKNOWLEDGED", ref,
                        "FIU reference " + Db.str(s, "regulator_reference"), Db.str(s, "updated_by")));
            }
        }
        if (c.hasNonNull("closed_at")) {
            items.add(item(c.get("closed_at").asString(), "CASE_CLOSED", "aml_case:" + caseId, "Case closed", Db.str(c, "updated_by")));
        }
        items.sort(Comparator.comparing(m -> java.time.OffsetDateTime.parse((String) m.get("at")).toInstant()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aml_case_id", caseId.toString());
        out.put("case_number", c.get("case_number").asString());
        out.put("status", c.get("status").asString());
        out.put("include_restricted", includeRestricted);
        out.put("items", items);
        return out;
    }

    private static Map<String, Object> item(String at, String type, String ref, String summary, String actor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", at);
        m.put("type", type);
        m.put("ref", ref);
        m.put("summary", summary);
        m.put("actor", actor);
        return m;
    }

    /** Deterministic case summary with citations; stored on the case. */
    @Transactional
    public Map<String, Object> summarise(UUID caseId) {
        JsonNode c = db.require("aml_case", caseId, true);
        NarrativeBuilder.Facts f = facts(c, null);
        NarrativeBuilder.Summary s = NarrativeBuilder.summary(f, Map.of("case", "aml_case:" + caseId));
        db.update("update aml_case set ai_summary = ?, updated_by = ? where id = ?", s.text(), TenantContext.principal(), caseId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aml_case_id", caseId.toString());
        out.put("summary", s.text());
        List<Map<String, Object>> cites = new ArrayList<>();
        int i = 1;
        for (NarrativeBuilder.Citation ci : s.citations()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i++);
            m.put("ref", ci.ref());
            m.put("text", ci.text());
            cites.add(m);
        }
        out.put("citations", cites);
        out.put("generator", "template-v1");
        return out;
    }

    /** Facts of a case (and optionally a SAR) for narratives and summaries. */
    NarrativeBuilder.Facts facts(JsonNode c, JsonNode sar) {
        UUID caseId = UUID.fromString(c.get("id").asString());
        List<NarrativeBuilder.Alert> alerts = db.list("select row_to_json(a)::text from aml_case_alert a where aml_case_id = ? order by created_at", caseId)
                .stream().map(a -> new NarrativeBuilder.Alert(a.get("id").asString(), a.get("source").asString(),
                        Db.str(a, "summary"), a.get("created_at").asString())).toList();
        int notes = db.jdbc().sql("select count(*) from aml_case_note where aml_case_id = ?").param(caseId).query(Integer.class).single();
        int evidence = db.jdbc().sql("select count(*) from aml_case_evidence where aml_case_id = ?").param(caseId).query(Integer.class).single();
        List<String> actions = new ArrayList<>();
        db.one("select row_to_json(d)::text from aml_case_disposition d where aml_case_id = ? order by created_at desc limit 1", caseId)
                .ifPresent(d -> {
                    actions.add(d.get("disposition").asString().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                            + " (" + d.get("approval_status").asString().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ")");
                    d.path("actions_taken").forEach(a -> actions.add(a.isString() ? a.asString() : a.toString()));
                });
        List<NarrativeBuilder.Subject> subjects = new ArrayList<>();
        List<NarrativeBuilder.Txn> txns = new ArrayList<>();
        BigDecimal amount = Db.dec(c, "total_amount");
        String currency = Db.str(c, "currency");
        if (sar != null) {
            sar.path("subjects").forEach(s -> subjects.add(new NarrativeBuilder.Subject(s.path("name").asString("unknown"),
                    Db.str(s, "type"), Db.str(s, "role"), Db.str(s, "country"), Db.str(s, "id_number"))));
            sar.path("transactions").forEach(t -> txns.add(new NarrativeBuilder.Txn(Db.str(t, "transaction_id"), Db.str(t, "date"),
                    Db.dec(t, "amount"), Db.str(t, "currency"), Db.str(t, "from"), Db.str(t, "to"), Db.str(t, "description"))));
            if (Db.dec(sar, "suspicious_amount") != null) {
                amount = Db.dec(sar, "suspicious_amount");
                currency = Db.str(sar, "currency");
            }
        }
        return new NarrativeBuilder.Facts(c.get("case_number").asString(), c.get("case_type").asString(),
                c.get("title").asString(), Db.str(c, "description"), Db.str(c, "customer_id"), amount, currency,
                Db.str(c, "opened_at"), sar == null ? null : Db.str(sar, "activity_start"),
                sar == null ? null : Db.str(sar, "activity_end"), sar == null ? null : Db.str(sar, "report_type"),
                sar == null ? null : Db.str(sar, "jurisdiction"), sar == null ? null : Db.str(sar, "regulator"),
                alerts, subjects, txns, notes, evidence, actions);
    }

    // ------------------------------------------------------------------ helpers

    private String publish(UUID caseId, String event) {
        JsonNode row = db.require("aml_case", caseId, false);
        outbox.publish(event, "aml_case", caseId, row);
        return db.text(row);
    }

    static void require(JsonNode c, String action) {
        String status = c.get("status").asString();
        if (!CaseRules.caseAllows(action, status)) {
            throw ApiException.conflict("invalid_transition", "Cannot " + action.replace('_', ' ') + " a case that is " + status);
        }
    }

    private static UUID id(JsonNode n) {
        return UUID.fromString(n.get("id").asString());
    }
}
