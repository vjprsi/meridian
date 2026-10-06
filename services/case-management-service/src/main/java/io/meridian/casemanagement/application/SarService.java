package io.meridian.casemanagement.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.casemanagement.domain.CaseRules;
import io.meridian.casemanagement.domain.GoAmlWriter;
import io.meridian.casemanagement.domain.NarrativeBuilder;
import io.meridian.casemanagement.infrastructure.Db;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * SAR / STR reports: drafting (narrative from case facts), MLRO approval (four-eyes against the preparer), filing,
 * FIU acknowledgement or rejection, and goAML XML export.
 */
@Service
public class SarService {
    static final int MIN_NARRATIVE = 80;
    /** Approval resource type for SAR / STR reports sent to the MLRO in workflow-service (G11). */
    public static final String SAR_APPROVAL = "sar_report";

    private final Db db;
    private final CrudEngine crud;
    private final CaseService cases;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final Approvals approvals;

    public SarService(Db db, CrudEngine crud, CaseService cases, OutboxWriter outbox, JsonMapper json, Approvals approvals) {
        this.approvals = approvals;
        this.db = db;
        this.crud = crud;
        this.cases = cases;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String create(JsonNode body) {
        ObjectNode v = crud.toColumns("sar_report", body);
        UUID caseId = Db.uuid(v, "aml_case_id");
        String type = Db.str(v, "report_type");
        if (caseId == null && !"CTR".equals(type) && !"LCTR".equals(type) && !"EFTR".equals(type)) {
            throw ApiException.unprocessable("aml_case_required", type + " reports must reference an AML case");
        }
        if (caseId != null) {
            JsonNode c = db.require("aml_case", caseId, false);
            if (!v.hasNonNull("suspicious_amount") && c.hasNonNull("total_amount")) {
                v.put("suspicious_amount", c.get("total_amount").asString());
                v.put("currency", Db.str(c, "currency"));
            }
        }
        if (v.hasNonNull("suspicious_amount") && !v.hasNonNull("currency")) {
            throw ApiException.unprocessable("currency_required", "currency is required with suspicious_amount");
        }
        validDates(v);
        if (!v.hasNonNull("filing_deadline")) {
            // most FIUs expect a SAR within 30 calendar days of detection
            v.put("filing_deadline", LocalDate.now(ZoneOffset.UTC).plusDays(30).toString());
        }
        v.put("status", "DRAFT");
        return crud.insert("sar_report", v);
    }

    @Transactional
    public String update(UUID id, JsonNode changes, int expectedVersion) {
        JsonNode s = db.require("sar_report", id, true);
        require(s, "edit");
        if (s.get("row_version").asInt() != expectedVersion) {
            throw ApiException.conflict("version_conflict", "If-Match " + expectedVersion + " does not match the current version; reload and retry");
        }
        ObjectNode v = crud.toColumns("sar_report", changes);
        for (String f : List.of("status", "ai_draft_narrative", "export_document_id", "prepared_by", "approved_by",
                "submitted_at", "acknowledged_at", "regulator_reference", "rejection_reason", "filing_channel")) {
            v.remove(f);
        }
        ObjectNode merged = ((ObjectNode) s.deepCopy()).setAll(v);
        validDates(merged);
        return crud.updateRow("sar_report", Map.of("id", id), v, expectedVersion).orElseThrow();
    }

    /** Builds the narrative from the case facts and stores it as the draft (the investigator edits narrative). */
    @Transactional
    public Map<String, Object> draftNarrative(UUID id) {
        JsonNode s = db.require("sar_report", id, true);
        require(s, "draft_narrative");
        UUID caseId = Db.uuid(s, "aml_case_id");
        if (caseId == null) {
            throw ApiException.unprocessable("aml_case_required", "Narratives are drafted from a case");
        }
        JsonNode c = db.require("aml_case", caseId, false);
        NarrativeBuilder.Facts facts = cases.facts(c, s);
        String narrative = NarrativeBuilder.narrative(facts);
        db.update("update sar_report set ai_draft_narrative = ?, updated_by = ? where id = ?", narrative, TenantContext.principal(), id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sar_report_id", id.toString());
        out.put("narrative", narrative);
        Map<String, Object> used = new LinkedHashMap<>();
        used.put("case_number", facts.caseNumber());
        used.put("alerts", facts.alerts().size());
        used.put("subjects", facts.subjects().size());
        used.put("transactions", facts.transactions().size());
        used.put("evidence", facts.evidence());
        used.put("notes", facts.notes());
        out.put("facts_used", used);
        out.put("generator", "template-v1");
        return out;
    }

    @Transactional
    public String submitForApproval(UUID id, String comment) {
        JsonNode s = db.require("sar_report", id, true);
        require(s, "submit_for_approval");
        String narrative = Db.str(s, "narrative");
        if (narrative == null || narrative.strip().length() < MIN_NARRATIVE) {
            throw ApiException.unprocessable("narrative_required",
                    "A narrative of at least " + MIN_NARRATIVE + " characters is required (edit the drafted narrative into 'narrative')");
        }
        if (!s.path("subjects").isArray() || s.path("subjects").isEmpty()) {
            throw ApiException.unprocessable("subjects_required", "At least one reported subject is required");
        }
        db.update("update sar_report set status = 'PENDING_APPROVAL', prepared_by = ?, updated_by = ? where id = ?",
                TenantContext.principal(), TenantContext.principal(), id);
        String out = publish(id, "risk.sar_report.submitted_for_approval");
        JsonNode submitted = db.require("sar_report", id, false);
        approvals.request(sarApproval(submitted), "sar:" + id + ":" + submitted.get("row_version").asInt());
        return out;
    }

    /**
     * Central maker-checker: the MLRO decides in the workflow inbox; SarApprovalHandler applies the decision.
     * Tipping-off: the payload carries ids, the report header and counts only - never the narrative, subjects or
     * transactions, which the MLRO reviews in case management. row_version fingerprints the submission.
     */
    private ApprovalSpec sarApproval(JsonNode s) {
        UUID caseId = Db.uuid(s, "aml_case_id");
        String caseNumber = caseId == null ? null
                : db.one("select json_build_object('n', case_number)::text from aml_case where id = ?", caseId)
                        .map(n -> n.get("n").asString()).orElse(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sar_report_id", s.get("id").asString());
        payload.put("aml_case_id", caseId == null ? null : caseId.toString());
        payload.put("case_number", caseNumber);
        payload.put("report_type", s.get("report_type").asString());
        payload.put("jurisdiction", Db.str(s, "jurisdiction"));
        payload.put("regulator", Db.str(s, "regulator"));
        payload.put("filing_deadline", Db.str(s, "filing_deadline"));
        payload.put("subjects", s.path("subjects").size());
        payload.put("transactions", s.path("transactions").size());
        payload.put("prepared_by", Db.str(s, "prepared_by"));
        payload.put("row_version", s.get("row_version").asInt());
        String title = s.get("report_type").asString() + " to " + Db.str(s, "regulator")
                + (caseNumber == null ? "" : " for case " + caseNumber);
        ApprovalSpec spec = ApprovalSpec.of(SAR_APPROVAL, UUID.fromString(s.get("id").asString()), "approve", title, payload)
                .withSummary("MLRO approval of a regulatory report before filing (content in case management)");
        BigDecimal amount = Db.dec(s, "suspicious_amount");
        return amount != null && Db.str(s, "currency") != null ? spec.withAmount(amount, Db.str(s, "currency")) : spec;
    }

    /** True while the report is PENDING_APPROVAL in the submission identified by its row_version. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, Integer rowVersion) {
        return db.one("select row_to_json(s)::text from sar_report s where id = ?", id)
                .filter(s -> "PENDING_APPROVAL".equals(Db.str(s, "status")))
                .filter(s -> rowVersion == null || rowVersion == s.get("row_version").asInt())
                .isPresent();
    }

    @Transactional
    public String approve(UUID id, String comment) {
        JsonNode s = db.require("sar_report", id, true);
        require(s, "approve");
        if (TenantContext.principal().equals(Db.str(s, "prepared_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The preparer cannot approve the report; the MLRO must");
        }
        db.update("update sar_report set status = 'APPROVED', approved_by = ?, updated_by = ? where id = ?",
                TenantContext.principal(), TenantContext.principal(), id);
        approvals.cancelOpen(SAR_APPROVAL, id, null, "approved directly");
        return publish(id, "risk.sar_report.approved");
    }

    @Transactional
    public String file(UUID id, String channel) {
        JsonNode s = db.require("sar_report", id, true);
        require(s, "file");
        String ch = channel == null ? "" : channel.toUpperCase(java.util.Locale.ROOT);
        if (!CaseRules.FILING_CHANNELS.contains(ch)) {
            throw ApiException.unprocessable("invalid_channel", "channel must be one of " + CaseRules.FILING_CHANNELS);
        }
        db.update("update sar_report set status = 'SUBMITTED', submitted_at = now(), filing_channel = ?, updated_by = ? where id = ?",
                ch, TenantContext.principal(), id);
        UUID caseId = Db.uuid(s, "aml_case_id");
        if (caseId != null) {
            // the case records that a report went to the FIU (risk.sar_report.filed carries the case id)
            db.update("""
                    update aml_case set status = 'SAR_FILED', updated_by = ?
                    where id = ? and status in ('OPEN', 'INVESTIGATING', 'ESCALATED')""", TenantContext.principal(), caseId);
        }
        return publish(id, "risk.sar_report.filed");
    }

    @Transactional
    public String acknowledge(UUID id, String reference) {
        JsonNode s = db.require("sar_report", id, true);
        require(s, "acknowledge");
        db.update("""
                update sar_report set status = 'ACKNOWLEDGED', acknowledged_at = now(), regulator_reference = ?,
                    rejection_reason = null, updated_by = ? where id = ?""", reference, TenantContext.principal(), id);
        return publish(id, "risk.sar_report.acknowledged");
    }

    @Transactional
    public String recordRejection(UUID id, String reason) {
        JsonNode s = db.require("sar_report", id, true);
        require(s, "record_rejection");
        db.update("""
                update sar_report set status = 'REJECTED', rejection_reason = ?, approved_by = null, updated_by = ?
                where id = ?""", reason, TenantContext.principal(), id);
        return publish(id, "risk.sar_report.rejected");
    }

    /** goAML XML of an approved (or later) report. */
    public String export(UUID id) {
        JsonNode s = db.require("sar_report", id, false);
        require(s, "export");
        List<NarrativeBuilder.Txn> txns = new ArrayList<>();
        s.path("transactions").forEach(t -> txns.add(new NarrativeBuilder.Txn(Db.str(t, "transaction_id"), Db.str(t, "date"),
                Db.dec(t, "amount"), Db.str(t, "currency"), Db.str(t, "from"), Db.str(t, "to"), Db.str(t, "description"))));
        List<NarrativeBuilder.Subject> subjects = new ArrayList<>();
        s.path("subjects").forEach(p -> subjects.add(new NarrativeBuilder.Subject(p.path("name").asString("unknown"),
                Db.str(p, "type"), Db.str(p, "role"), Db.str(p, "country"), Db.str(p, "id_number"))));
        List<String> indicators = new ArrayList<>();
        UUID caseId = Db.uuid(s, "aml_case_id");
        String action = "Report filed; account under enhanced monitoring.";
        if (caseId != null) {
            JsonNode c = db.require("aml_case", caseId, false);
            indicators.add(c.get("case_type").asString());
            db.list("select row_to_json(a)::text from aml_case_alert a where aml_case_id = ?", caseId)
                    .forEach(a -> {
                        if (!indicators.contains(a.get("source").asString())) {
                            indicators.add(a.get("source").asString());
                        }
                    });
            var d = db.one("select row_to_json(d)::text from aml_case_disposition d where aml_case_id = ? order by created_at desc limit 1", caseId);
            if (d.isPresent()) {
                List<String> acts = new ArrayList<>();
                d.get().path("actions_taken").forEach(a -> acts.add(a.isString() ? a.asString() : a.toString()));
                if (!acts.isEmpty()) {
                    action = String.join("; ", acts);
                }
            }
        }
        return GoAmlWriter.write(new GoAmlWriter.Report(TenantContext.require().toString(), s.get("report_type").asString(),
                s.get("id").asString(), io.meridian.common.time.Clocks.now().withNano(0).toString(),
                Db.str(s, "currency") == null ? "USD" : Db.str(s, "currency"),
                Db.str(s, "prepared_by") == null ? "unknown" : Db.str(s, "prepared_by"),
                Db.str(s, "narrative"), action, Db.str(s, "regulator_reference"), txns, subjects, indicators));
    }

    private String publish(UUID id, String event) {
        JsonNode row = db.require("sar_report", id, false);
        outbox.publish(event, "sar_report", id, row);
        return db.text(row);
    }

    private static void require(JsonNode s, String action) {
        String status = s.get("status").asString();
        if (!CaseRules.sarAllows(action, status)) {
            throw ApiException.conflict("invalid_transition", "Cannot " + action.replace('_', ' ') + " a report that is " + status);
        }
    }

    private static void validDates(JsonNode v) {
        String a = Db.str(v, "activity_start");
        String b = Db.str(v, "activity_end");
        if (a != null && b != null && LocalDate.parse(b).isBefore(LocalDate.parse(a))) {
            throw ApiException.unprocessable("invalid_period", "activity_end must not be before activity_start");
        }
    }
}
