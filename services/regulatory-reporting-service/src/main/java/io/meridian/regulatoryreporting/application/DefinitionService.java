package io.meridian.regulatoryreporting.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.regulatoryreporting.domain.DefinitionException;
import io.meridian.regulatoryreporting.domain.FilingCalendar;
import io.meridian.regulatoryreporting.domain.Lifecycles;
import io.meridian.regulatoryreporting.domain.ReportEngine;
import io.meridian.regulatoryreporting.domain.ReportRenderer;
import io.meridian.regulatoryreporting.domain.ReportValidator;
import io.meridian.regulatoryreporting.infrastructure.Store;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Report definitions and their versions. A maker creates DRAFT versions (data sources, field mappings, validation
 * rules are compiled and rejected when invalid); a different principal publishes one, which supersedes the version in
 * force from the new version's effective date. Also builds the filing calendar.
 */
@Service
public class DefinitionService {
    private final Store store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final Approvals approvals;

    /** Approval resource type for definition versions in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "report_definition_version";

    public DefinitionService(Store store, CrudEngine crud, OutboxWriter outbox, JsonMapper json, Approvals approvals) {
        this.approvals = approvals;
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
    }

    /** Business checks on definition create / update payloads (only the fields present are checked). */
    public void checkDefinition(JsonNode req, String frequencyFallback) {
        String format = Fields.text(req, "format");
        if (format != null && !ReportRenderer.renderable(format)) {
            throw Fields.invalid("format", "unsupported_format", "format " + format
                    + " cannot be rendered by the service; use XBRL, XML, CSV or JSON");
        }
        String frequency = Optional.ofNullable(Fields.text(req, "frequency")).orElse(frequencyFallback);
        String rule = Fields.text(req, "deadline_rule");
        if (rule != null) {
            try {
                FilingCalendar.parse(rule, frequency == null ? "MONTHLY" : frequency);
            } catch (IllegalArgumentException e) {
                throw Fields.invalid("deadline_rule", "invalid_deadline_rule", e.getMessage());
            }
        }
        Integer offset = Fields.integer(req, "filing_offset_days");
        if (offset != null && (offset < 0 || offset > 365)) {
            throw Fields.invalid("filing_offset_days", "out_of_range", "filing_offset_days must be 0-365");
        }
    }

    /** Maker step: a new DRAFT version with the next version number. */
    @Transactional
    public String addVersion(UUID definitionId, JsonNode req) {
        JsonNode def = store.requireLocked("report_definition", definitionId);
        if ("DEPRECATED".equals(def.get("status").asString())) {
            throw ApiException.conflict("definition_deprecated", "Report definition " + def.get("code").asString() + " is deprecated");
        }
        LocalDate from = Fields.requireDate(req, "effective_from");
        LocalDate to = Fields.date(req, "effective_to");
        if (to != null && to.isBefore(from)) {
            throw Fields.invalid("effective_to", "invalid_range", "effective_to must not be before effective_from");
        }
        compile(req);
        int next = (int) store.count("select coalesce(max(definition_version), 0) + 1 from report_definition_version "
                + "where report_definition_id = ?", definitionId);
        ObjectNode v = json.createObjectNode();
        v.put("report_definition_id", definitionId.toString());
        v.put("definition_version", next);
        v.put("status", "DRAFT");
        for (String f : List.of("template_document_id", "xbrl_taxonomy", "effective_from", "effective_to", "change_note")) {
            if (req.hasNonNull(f)) {
                v.set(f, req.get(f));
            }
        }
        v.set("data_sources", req.get("data_sources"));
        v.set("field_mappings", req.get("field_mappings"));
        v.set("validation_rules", req.hasNonNull("validation_rules") ? req.get("validation_rules") : json.createArrayNode());
        String inserted = crud.insert("report_definition_version", v);
        // Central maker-checker: versions are immutable, so the new DRAFT is the submission; the checker publishes it
        // from the workflow inbox and DefinitionVersionApprovalHandler applies the decision.
        JsonNode row = json.readTree(inserted);
        UUID versionId = UUID.fromString(row.get("id").asString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("report_definition_id", definitionId.toString());
        payload.put("report_code", def.get("code").asString());
        payload.put("definition_version", next);
        payload.put("effective_from", from.toString());
        payload.put("change_note", Fields.text(req, "change_note"));
        payload.put("row_version", row.path("row_version").asInt(1));
        approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, versionId, "publish",
                "Report definition " + def.get("code").asString() + " v" + next, payload),
                "publish:" + versionId + ":" + row.path("row_version").asInt(1));
        return inserted;
    }

    /** Publishes a version identified by id (workflow decisions); same checks as {@link #publish}. */
    @Transactional
    public String publishVersion(UUID versionId, String comment) {
        JsonNode version = store.require("report_definition_version", versionId);
        return publish(RunService.uuid(version, "report_definition_id"), version.get("definition_version").asInt(), comment);
    }

    /** True while the version is an unpublished DRAFT of a live definition with the submitted row_version. */
    @Transactional(readOnly = true)
    public boolean awaitsPublication(UUID versionId, Integer rowVersion) {
        return store.find("report_definition_version", versionId)
                .filter(v -> "DRAFT".equals(v.path("status").asString()))
                .filter(v -> rowVersion == null || rowVersion == v.path("row_version").asInt())
                .filter(v -> store.find("report_definition", RunService.uuid(v, "report_definition_id"))
                        .map(d -> !"DEPRECATED".equals(d.path("status").asString())).orElse(false))
                .isPresent();
    }

    /** Compiles sources, mappings and rules of a version payload; 422 invalid_definition listing every problem. */
    public Compiled compile(JsonNode version) {
        try {
            List<Map<String, Object>> sources = list(version.get("data_sources"), "data_sources");
            Set<String> names = ReportEngine.checkSources(sources, SourceCollector.ALLOWED_SERVICES);
            List<ReportEngine.Mapping> mappings = ReportEngine.compile(list(version.get("field_mappings"), "field_mappings"), names);
            Set<String> refs = new LinkedHashSet<>();
            mappings.forEach(m -> refs.add(m.cellRef()));
            List<ReportValidator.Rule> rules = ReportValidator.compile(
                    version.hasNonNull("validation_rules") ? list(version.get("validation_rules"), "validation_rules") : List.of(), refs);
            return new Compiled(mappings, rules);
        } catch (DefinitionException e) {
            throw new ApiException(422, "invalid_definition", e.getMessage(), e.problems().stream()
                    .map(p -> new ApiException.FieldError(p.field(), "invalid", p.message())).toList());
        }
    }

    public record Compiled(List<ReportEngine.Mapping> mappings, List<ReportValidator.Rule> rules) {}

    private List<Map<String, Object>> list(JsonNode n, String field) {
        if (n == null || n.isNull()) {
            return List.of();
        }
        if (!n.isArray()) {
            throw new DefinitionException(List.of(new DefinitionException.Problem(field, field + " must be a list")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n.size(); i++) {
            if (!n.get(i).isObject()) {
                throw new DefinitionException(List.of(new DefinitionException.Problem(field + "[" + i + "]", "must be an object")));
            }
            out.add(json.convertValue(n.get(i), new TypeReference<Map<String, Object>>() {}));
        }
        return out;
    }

    /**
     * Checker step: publishes a DRAFT version. The publisher must not be the version's author (four eyes). The version
     * in force is superseded from the day before the new effective date; the definition becomes ACTIVE.
     */
    @Transactional
    public String publish(UUID definitionId, int versionNo, String comment) {
        JsonNode def = store.requireLocked("report_definition", definitionId);
        if ("DEPRECATED".equals(def.get("status").asString())) {
            throw ApiException.conflict("definition_deprecated", "Report definition " + def.get("code").asString() + " is deprecated");
        }
        JsonNode version = store.row("""
                select row_to_json(t)::text from report_definition_version t
                where t.report_definition_id = ? and t.definition_version = ? for update""", definitionId, versionNo)
                .orElseThrow(() -> ApiException.unprocessable("version_not_found",
                        "Report definition has no version " + versionNo));
        Lifecycles.version(version.get("status").asString(), "ACTIVE");
        if (TenantContext.principal().equals(Fields.text(version, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The author of a definition version cannot publish it");
        }
        compile(version);
        LocalDate from = LocalDate.parse(version.get("effective_from").asString());
        for (JsonNode active : store.rows("""
                select row_to_json(t)::text from report_definition_version t
                where t.report_definition_id = ? and t.status = 'ACTIVE' for update""", definitionId)) {
            LocalDate activeFrom = LocalDate.parse(active.get("effective_from").asString());
            if (!from.isAfter(activeFrom)) {
                throw ApiException.unprocessable("effective_from_not_after_active", "effective_from " + from
                        + " must be after the effective_from (" + activeFrom + ") of active version "
                        + active.get("definition_version").asInt());
            }
            store.update("update report_definition_version set status = 'SUPERSEDED', effective_to = ?, updated_by = ? "
                    + "where id = ?", from.minusDays(1), TenantContext.principal(), UUID.fromString(active.get("id").asString()));
        }
        JsonNode published = store.updateReturning("""
                update report_definition_version t set status = 'ACTIVE', approved_by = ?, approved_at = now(), updated_by = ?
                where t.id = ? returning row_to_json(t)::text""",
                TenantContext.principal(), TenantContext.principal(), UUID.fromString(version.get("id").asString()));
        store.update("update report_definition set status = 'ACTIVE', active_version = ?, updated_by = ? where id = ?",
                versionNo, TenantContext.principal(), definitionId);
        ObjectNode payload = ((ObjectNode) published).deepCopy();
        payload.put("report_code", def.get("code").asString());
        if (comment != null) {
            payload.put("comment", comment);
        }
        outbox.publish("reporting.report_definition.published", "report_definition_version",
                UUID.fromString(published.get("id").asString()), payload);
        approvals.cancelOpen(APPROVAL_RESOURCE, UUID.fromString(published.get("id").asString()), null, "published directly");
        return published.toString();
    }

    /**
     * Checker step: rejects a DRAFT version (four eyes against its author). Versions are immutable, so a REJECTED
     * version is final: the maker creates a corrected version.
     */
    @Transactional
    public String rejectVersion(UUID versionId, String reason) {
        JsonNode version = store.requireLocked("report_definition_version", versionId);
        Lifecycles.version(version.get("status").asString(), "REJECTED");
        if (TenantContext.principal().equals(Fields.text(version, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The author of a definition version cannot reject it");
        }
        JsonNode rejected = store.updateReturning("""
                update report_definition_version t set status = 'REJECTED', rejected_by = ?, rejected_at = ?,
                rejection_reason = ?, updated_by = ? where t.id = ? returning row_to_json(t)::text""",
                TenantContext.principal(), io.meridian.common.time.Clocks.now(), reason, TenantContext.principal(), versionId);
        outbox.publish("reporting.report_definition.version_rejected", "report_definition_version", versionId, rejected);
        approvals.cancelOpen(APPROVAL_RESOURCE, versionId, null, "rejected directly");
        return rejected.toString();
    }

    /** Deprecates a definition: no new runs or versions; its schedules are deactivated. */
    @Transactional
    public String deprecate(UUID definitionId, String reason) {
        JsonNode def = store.requireLocked("report_definition", definitionId);
        if ("DEPRECATED".equals(def.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "Report definition is already DEPRECATED");
        }
        JsonNode row = store.updateReturning("""
                update report_definition t set status = 'DEPRECATED', status_reason = ?, updated_by = ?
                where t.id = ? returning row_to_json(t)::text""", reason, TenantContext.principal(), definitionId);
        store.update("update report_definition_version set status = 'DEPRECATED', updated_by = ? "
                + "where report_definition_id = ? and status = 'ACTIVE'", TenantContext.principal(), definitionId);
        store.update("update report_schedule set is_active = false, updated_by = ? where report_definition_id = ? and is_active",
                TenantContext.principal(), definitionId);
        outbox.publish("reporting.report_definition.deprecated", "report_definition", definitionId, row);
        for (JsonNode draft : !approvals.centralised() ? List.<JsonNode>of() : store.rows("select row_to_json(t)::text from report_definition_version t "
                + "where t.report_definition_id = ? and t.status = 'DRAFT'", definitionId)) {
            approvals.cancelOpen(APPROVAL_RESOURCE, RunService.uuid(draft, "id"), null, "definition deprecated");
        }
        return row.toString();
    }

    /** The version in force at a reporting date: ACTIVE or SUPERSEDED, effective range containing the date. */
    public Optional<JsonNode> versionAt(UUID definitionId, LocalDate asOf) {
        return store.row("""
                select row_to_json(t)::text from report_definition_version t
                where t.report_definition_id = ? and t.status in ('ACTIVE', 'SUPERSEDED')
                  and t.effective_from <= ? and (t.effective_to is null or t.effective_to >= ?)
                order by t.definition_version desc limit 1""", definitionId, asOf, asOf);
    }

    /**
     * Filing calendar of a definition: every reporting period whose due date falls in [from, to], per legal entity
     * (the requested one, else those with schedules), with the latest run and submission status.
     */
    @Transactional(readOnly = true)
    public ObjectNode calendar(UUID definitionId, LocalDate from, LocalDate to, UUID legalEntityId) {
        JsonNode def = store.require("report_definition", definitionId);
        LocalDate today = store.scalar("select current_date", LocalDate.class).orElseThrow();
        LocalDate start = from == null ? today : from;
        LocalDate end = to == null ? start.plusDays(90) : to;
        if (end.isBefore(start)) {
            throw Fields.invalid("to", "invalid_range", "to must not be before from");
        }
        if (start.plusDays(731).isBefore(end)) {
            throw Fields.invalid("to", "range_too_long", "The calendar spans at most two years");
        }
        String frequency = def.get("frequency").asString();
        String rule = Fields.text(def, "deadline_rule");
        int offset = def.path("filing_offset_days").asInt(0);
        List<UUID> entities = new ArrayList<>();
        if (legalEntityId != null) {
            entities.add(legalEntityId);
        } else {
            store.rows("select json_build_object('id', legal_entity_id)::text from report_schedule "
                            + "where report_definition_id = ? group by legal_entity_id order by legal_entity_id", definitionId)
                    .forEach(n -> entities.add(UUID.fromString(n.get("id").asString())));
        }
        ObjectNode out = json.createObjectNode();
        out.put("report_definition_id", definitionId.toString());
        out.put("code", def.get("code").asString());
        out.put("frequency", frequency);
        out.put("deadline_rule", rule);
        out.put("filing_offset_days", offset);
        out.put("from", start.toString());
        out.put("to", end.toString());
        out.put("today", today.toString());
        ArrayNode entries = out.putArray("entries");
        // Periods ending up to a year (plus the longest deadline) before the window can still fall due inside it.
        for (LocalDate periodEnd : FilingCalendar.periodEnds(frequency, start.minusDays(500), end)) {
            LocalDate due = FilingCalendar.dueDate(rule, frequency, periodEnd, offset);
            if (due.isBefore(start) || due.isAfter(end)) {
                continue;
            }
            List<UUID> les = entities.isEmpty() ? java.util.Collections.singletonList(null) : entities;
            for (UUID le : les) {
                ObjectNode e = entries.addObject();
                if (le != null) {
                    e.put("legal_entity_id", le.toString());
                }
                e.put("period_start", FilingCalendar.periodStart(frequency, periodEnd).toString());
                e.put("period_end", periodEnd.toString());
                e.put("as_of_date", periodEnd.toString());
                e.put("due_date", due.toString());
                e.put("days_to_due", java.time.temporal.ChronoUnit.DAYS.between(today, due));
                String status = due.isBefore(today) ? "OVERDUE" : "DUE";
                if (le != null) {
                    Optional<JsonNode> run = store.row("""
                            select row_to_json(t)::text from report_run t where t.report_definition_id = ?
                              and t.legal_entity_id = ? and t.as_of_date = ? and t.superseded_by_run_id is null
                              and t.status not in ('CANCELLED') order by t.id desc limit 1""", definitionId, le, periodEnd);
                    if (run.isPresent()) {
                        e.put("report_run_id", run.get().get("id").asString());
                        e.put("run_status", run.get().get("status").asString());
                        status = "IN_PROGRESS";
                        Optional<JsonNode> sub = store.row("""
                                select row_to_json(s)::text from report_submission s where s.report_run_id = ?
                                order by s.id desc limit 1""", UUID.fromString(run.get().get("id").asString()));
                        if (sub.isPresent()) {
                            String ss = sub.get().get("status").asString();
                            e.put("report_submission_id", sub.get().get("id").asString());
                            e.put("submission_status", ss);
                            if (Lifecycles.SUBMISSION_FILED.contains(ss)) {
                                status = "FILED";
                            }
                        }
                        if (!"FILED".equals(status) && due.isBefore(today)) {
                            status = "OVERDUE";
                        }
                    }
                }
                e.put("status", status);
            }
        }
        out.put("count", entries.size());
        return out;
    }
}
