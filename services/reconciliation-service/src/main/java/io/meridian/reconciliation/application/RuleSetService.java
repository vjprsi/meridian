package io.meridian.reconciliation.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.FourEyes;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.events.OutboxWriter;
import io.meridian.reconciliation.domain.matching.RuleSet;
import io.meridian.reconciliation.infrastructure.ReconStore;
import io.meridian.web.crud.CrudEngine;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Versioned matching rule sets with four-eyes publication.
 * <pre>
 *   DRAFT | REJECTED --submit--> PENDING_APPROVAL --approve (checker != submitter)--> PUBLISHED --newer published--> SUPERSEDED
 *                                PENDING_APPROVAL --reject (checker != submitter)--> REJECTED (editable, resubmittable)
 * </pre>
 * Each version gets the next number of its scope (a source, or the tenant default). Only DRAFT / REJECTED versions
 * can be edited (an edit of a REJECTED version returns it to DRAFT). Submission opens a central approval request
 * (workflow-service) fingerprinted by the configuration checksum; a decision for another checksum is ignored.
 * A run uses the PUBLISHED version of its source, else the tenant default, else the legacy recon_rule rows.
 */
@Service
public class RuleSetService {
    public static final String APPROVAL_RESOURCE = "recon_rule_set";
    private static final String T = "recon_rule_set";
    private static final Set<String> EDITABLE = Set.of("DRAFT", "REJECTED");

    private final ReconStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final Approvals approvals;
    private final JsonMapper json;

    public RuleSetService(ReconStore store, CrudEngine crud, OutboxWriter outbox, Approvals approvals, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.approvals = approvals;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        if (!v.path("rules").isArray() || v.path("rules").isEmpty()) {
            v.set("rules", RuleSetCodec.encode(RuleSet.standard().rules(), json));
        }
        UUID sourceId = Fields.uuid(v, "recon_source_id");
        if (sourceId != null && store.find("recon_source", sourceId).isEmpty()) {
            throw ApiException.unprocessable("source_not_found", "Reconciliation source " + sourceId + " does not exist");
        }
        RuleSetCodec.validate(v);
        store.advisoryLock("recon_rule_set:" + TenantContext.require() + ":" + sourceId);
        long next = store.count("select coalesce(max(version), 0) + 1 from recon_rule_set where recon_source_id is not distinct from ?",
                sourceId);
        v.put("version", (int) next);
        v.put("status", "DRAFT");
        v.put("checksum", checksum(v));
        return crud.insert(T, v);
    }

    @Transactional
    public String update(UUID id, String ifMatch, Object body) {
        int version = Fields.ifMatch(ifMatch);
        JsonNode cur = store.requireLocked(T, id);
        String status = cur.get("status").asString();
        if (!EDITABLE.contains(status)) {
            throw ApiException.conflict("rule_set_not_editable", "Rule set version " + id + " is " + status
                    + "; create a new version instead");
        }
        ObjectNode patch = crud.toColumns(T, body);
        if (patch.has("recon_source_id") && !patch.get("recon_source_id").asString().equals(cur.path("recon_source_id").asString(""))) {
            throw ApiException.unprocessable("field_not_updatable", "recon_source_id cannot change; create a version for the other scope");
        }
        ObjectNode merged = ((ObjectNode) cur).deepCopy();
        patch.propertyNames().forEach(n -> merged.set(n, patch.get(n)));
        RuleSetCodec.validate(merged);
        patch.put("checksum", checksum(merged));
        if ("REJECTED".equals(status)) {
            patch.put("status", "DRAFT");
        }
        return crud.updateRow(T, Map.of("id", id), patch, version)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match " + version + " does not match the current version; reload and retry"));
    }

    /** DRAFT / REJECTED -> PENDING_APPROVAL; opens the central approval request. */
    @Transactional
    public String submit(UUID id) {
        JsonNode cur = store.requireLocked(T, id);
        String status = cur.get("status").asString();
        if (!EDITABLE.contains(status)) {
            throw ApiException.conflict("invalid_transition", "Cannot submit a rule set version in status " + status);
        }
        RuleSetCodec.validate(cur);
        String checksum = checksum(cur);
        String now = Clocks.now().toString();
        store.update("""
                update recon_rule_set set status = 'PENDING_APPROVAL', checksum = ?, submitted_by = ?,
                       submitted_at = ?::timestamptz, rejected_by = null, rejection_reason = null, updated_by = ?
                where id = ?""", checksum, TenantContext.principal(), now, TenantContext.principal(), id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("checksum", checksum);
        payload.put("version", cur.get("version").asInt());
        payload.put("name", cur.get("name").asString());
        payload.put("recon_source_id", Fields.text(cur, "recon_source_id"));
        payload.put("rules", cur.get("rules").size());
        payload.put("auto_match_threshold", Fields.text(cur, "auto_match_threshold"));
        JsonNode request = approvals.request(ApprovalSpec.of(APPROVAL_RESOURCE, id, "publish",
                "Publish reconciliation rule set " + cur.get("name").asString() + " v" + cur.get("version").asInt(), payload)
                .withSummary(cur.get("rules").size() + " rules, auto-match threshold "
                        + Fields.text(cur, "auto_match_threshold")), "publish:" + id + ":" + checksum + ":" + now);
        UUID requestId = Approvals.idOf(request);
        if (requestId != null) {
            store.update("update recon_rule_set set approval_request_id = ? where id = ?", requestId, id);
            approvals.cancelOpenExcept(APPROVAL_RESOURCE, id, null, requestId, "superseded by a new submission");
        }
        JsonNode row = store.require(T, id);
        outbox.publish("ledger.recon_rule_set.submitted", T, id, row);
        return row.toString();
    }

    /** Publishes a submitted version (checker != submitter) and supersedes the published version of its scope. */
    @Transactional
    public String approve(UUID id, String note) {
        JsonNode cur = store.requireLocked(T, id);
        requirePending(cur);
        FourEyes.require(Fields.text(cur, "submitted_by"), TenantContext.principal(),
                "The submitter of a rule set version cannot approve it");
        UUID sourceId = Fields.uuid(cur, "recon_source_id");
        store.advisoryLock("recon_rule_set:" + TenantContext.require() + ":" + sourceId);
        String now = Clocks.now().toString();
        for (UUID old : store.ids("""
                select id from recon_rule_set where status = 'PUBLISHED' and recon_source_id is not distinct from ?
                  and id <> ?""", sourceId, id)) {
            store.update("""
                    update recon_rule_set set status = 'SUPERSEDED', superseded_at = ?::timestamptz, updated_by = ?
                    where id = ?""", now, TenantContext.principal(), old);
        }
        store.update("""
                update recon_rule_set set status = 'PUBLISHED', approved_by = ?, published_at = ?::timestamptz,
                       description = coalesce(description, ?), updated_by = ?
                where id = ?""", TenantContext.principal(), now, note, TenantContext.principal(), id);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "decided in reconciliation-service");
        JsonNode row = store.require(T, id);
        outbox.publish("ledger.recon_rule_set.published", T, id, row);
        return row.toString();
    }

    @Transactional
    public String reject(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw Fields.invalid("reason", "required", "A reason is required to reject a rule set version");
        }
        JsonNode cur = store.requireLocked(T, id);
        requirePending(cur);
        FourEyes.require(Fields.text(cur, "submitted_by"), TenantContext.principal(),
                "The submitter of a rule set version cannot reject it; edit it instead");
        store.update("""
                update recon_rule_set set status = 'REJECTED', rejected_by = ?, rejection_reason = ?, updated_by = ?
                where id = ?""", TenantContext.principal(), reason.length() > 500 ? reason.substring(0, 500) : reason,
                TenantContext.principal(), id);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "decided in reconciliation-service");
        JsonNode row = store.require(T, id);
        outbox.publish("ledger.recon_rule_set.rejected", T, id, row);
        return row.toString();
    }

    /** True while the version waits for the decision on this checksum (central decisions on stale content are ignored). */
    public boolean awaitsApproval(UUID id, String checksum) {
        return store.find(T, id).map(r -> "PENDING_APPROVAL".equals(r.get("status").asString())
                && (checksum == null || checksum.equals(Fields.text(r, "checksum")))).orElse(false);
    }

    /** The rule set a run of the source uses: its own PUBLISHED version, else the tenant default. */
    public Optional<RuleSet> published(UUID sourceId) {
        Optional<JsonNode> row = store.row("""
                select row_to_json(t)::text from recon_rule_set t
                where t.status = 'PUBLISHED' and (t.recon_source_id = ? or t.recon_source_id is null)
                order by (t.recon_source_id is null), t.version desc limit 1""", sourceId);
        return row.map(RuleSetCodec::decode);
    }

    public RuleSet load(UUID id) {
        return RuleSetCodec.decode(store.require(T, id));
    }

    private static void requirePending(JsonNode cur) {
        String status = cur.get("status").asString();
        if (!"PENDING_APPROVAL".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Rule set version is " + status + ", not PENDING_APPROVAL");
        }
    }

    /** SHA-256 over what the version does: thresholds and rules (not names / notes). */
    private String checksum(JsonNode row) {
        ObjectNode c = json.createObjectNode();
        c.set("rules", row.path("rules"));
        c.put("auto_match_threshold", plain(row.path("auto_match_threshold"), "0.9"));
        c.put("suggest_threshold", plain(row.path("suggest_threshold"), "0.5"));
        return FileService.sha256(json.writeValueAsString(c).getBytes(StandardCharsets.UTF_8));
    }

    private static String plain(JsonNode n, String dflt) {
        String s = n.isMissingNode() || n.isNull() ? dflt : n.asString();
        return new java.math.BigDecimal(s).stripTrailingZeros().toPlainString();
    }
}
