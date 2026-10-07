package io.meridian.reconciliation.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.reconciliation.domain.ExceptionLifecycle;
import io.meridian.reconciliation.infrastructure.ReconStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Matches are append-only. A match is undone by a compensating record (same record sets, unmatched_at set), after
 * which its records return to the matching queue. Every match publishes {@code ledger.recon_match.created}, every
 * undo {@code ledger.recon_match.unmatched} (keyed by the undone match). Every path that creates matches serialises on a per-tenant lock
 * so a record can never be in two active matches.
 */
@Service
public class MatchService {
    private static final Set<String> TYPES = Set.of("ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE", "MANY_TO_MANY", "MANUAL");

    private final ReconStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public MatchService(ReconStore store, CrudEngine crud, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
    }

    /** Manual match confirmed by the calling principal. */
    @Transactional
    public String createManual(JsonNode req) {
        String type = Fields.requireText(req, "match_type");
        if (!TYPES.contains(type)) {
            throw Fields.invalid("match_type", "invalid_enum", "match_type must be one of " + TYPES);
        }
        if (req.hasNonNull("unmatched_at")) {
            throw ApiException.unprocessable("field_not_allowed", "unmatched_at is set by POST /matches/{id}/unmatch");
        }
        List<UUID> ext = uuids(req.path("external_record_ids"), "external_record_ids");
        List<UUID> in = uuids(req.path("internal_entry_ids"), "internal_entry_ids");
        long found = store.count("select count(*) from recon_record where id = any(?)", (Object) ext.toArray(UUID[]::new));
        if (found != ext.size()) {
            throw ApiException.unprocessable("record_not_found", "Some external_record_ids do not exist");
        }
        UUID runId = Fields.uuid(req, "recon_run_id");
        if (runId != null && store.find("recon_run", runId).isEmpty()) {
            throw ApiException.unprocessable("run_not_found", "Reconciliation run " + runId + " does not exist");
        }
        UUID ruleId = Fields.uuid(req, "recon_rule_id");
        if (ruleId != null && store.find("recon_rule", ruleId).isEmpty()) {
            throw ApiException.unprocessable("rule_not_found", "Reconciliation rule " + ruleId + " does not exist");
        }
        lockMatching();
        requireUnmatched(ext, in);
        BigDecimal diff = Fields.decimal(req, "difference");
        BigDecimal conf = Fields.decimal(req, "confidence");
        String row = insertMatch(runId, ruleId, type, ext, in, diff == null ? BigDecimal.ZERO : diff, conf,
                TenantContext.principal());
        resolveOpenExceptions(ext, in, "Matched manually by " + TenantContext.principal() + " (match "
                + json.readTree(row).get("id").asString() + ")");
        refreshFileStatus(ext);
        return row;
    }

    /** Undoes an active match with a compensating record; its records go back to the queue. */
    @Transactional
    public String unmatch(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw Fields.invalid("reason", "required", "A reason is required to undo a match");
        }
        lockMatching();
        JsonNode m = store.require("recon_match", id);
        if (m.hasNonNull("unmatched_at")) {
            throw ApiException.conflict("not_unmatchable", "Match " + id + " is itself a compensating (unmatch) record");
        }
        if (store.count("select count(*) from (" + ReconStore.ACTIVE_MATCHES + ") a where a.id = ?", id) == 0) {
            throw ApiException.conflict("already_unmatched", "Match " + id + " was already undone");
        }
        ObjectNode v = json.createObjectNode();
        copy(m, v, "recon_run_id", "recon_rule_id", "match_type", "external_record_ids", "internal_entry_ids",
                "difference", "confidence");
        v.put("confirmed_by", TenantContext.principal());
        v.put("unmatched_at", io.meridian.common.time.Clocks.now().toString());
        String row = crud.insert("recon_match", v);
        JsonNode compensating = json.readTree(row);
        ObjectNode payload = ((ObjectNode) compensating).deepCopy();
        payload.put("unmatched_match_id", id.toString());
        payload.put("reason", reason);
        outbox.publish("ledger.recon_match.unmatched", "recon_match", id, payload);
        List<UUID> ext = new ArrayList<>();
        m.get("external_record_ids").forEach(e -> ext.add(UUID.fromString(e.asString())));
        refreshFileStatus(ext);
        return row;
    }

    // ------------------------------------------------------------------ shared with runs and exceptions

    void lockMatching() {
        store.advisoryLock("recon_matching:" + TenantContext.require());
    }

    void requireUnmatched(Collection<UUID> ext, Collection<UUID> in) {
        Set<UUID> matchedExt = store.matchedExternalIds();
        Set<UUID> matchedInt = store.matchedInternalIds();
        for (UUID e : ext) {
            if (matchedExt.contains(e)) {
                throw ApiException.conflict("already_matched", "External record " + e + " is already matched");
            }
        }
        for (UUID i : in) {
            if (matchedInt.contains(i)) {
                throw ApiException.conflict("already_matched", "Internal entry " + i + " is already matched");
            }
        }
    }

    String insertMatch(UUID runId, UUID ruleId, String type, Collection<UUID> ext, Collection<UUID> in,
                       BigDecimal difference, BigDecimal confidence, String confirmedBy) {
        return insertMatch(runId, ruleId, type, ext, in, difference, confidence, confirmedBy, null, null, null);
    }

    /** A match produced by a rule set version: rule code and explanation (keys and score components) are kept. */
    String insertMatch(UUID runId, UUID ruleId, String type, Collection<UUID> ext, Collection<UUID> in,
                       BigDecimal difference, BigDecimal confidence, String confirmedBy, UUID ruleSetId,
                       String ruleCode, JsonNode explanation) {
        ObjectNode v = json.createObjectNode();
        if (ruleSetId != null) {
            v.put("recon_rule_set_id", ruleSetId.toString());
        }
        if (ruleCode != null) {
            v.put("rule_code", ruleCode);
        }
        if (explanation != null) {
            v.set("explanation", explanation);
        }
        if (runId != null) {
            v.put("recon_run_id", runId.toString());
        }
        if (ruleId != null) {
            v.put("recon_rule_id", ruleId.toString());
        }
        v.put("match_type", type);
        v.set("external_record_ids", sortedArray(ext));
        v.set("internal_entry_ids", sortedArray(in));
        v.put("difference", difference.toPlainString());
        if (confidence != null) {
            v.put("confidence", confidence.toPlainString());
        }
        if (confirmedBy != null) {
            v.put("confirmed_by", confirmedBy);
        }
        String row = crud.insert("recon_match", v);
        JsonNode match = json.readTree(row);
        outbox.publish("ledger.recon_match.created", "recon_match", UUID.fromString(match.get("id").asString()), match);
        return row;
    }

    /** Resolves open breaks that reference any of the records now matched. */
    int resolveOpenExceptions(Collection<UUID> ext, Collection<UUID> in, String note) {
        List<JsonNode> open = store.rows("""
                select row_to_json(t)::text from recon_exception t
                where t.status in ('OPEN', 'INVESTIGATING')
                  and (t.external_record_id = any(?) or t.internal_entry_id = any(?)) for update""",
                (Object) ext.toArray(UUID[]::new), (Object) in.toArray(UUID[]::new));
        for (JsonNode e : open) {
            UUID id = UUID.fromString(e.get("id").asString());
            ExceptionLifecycle.next(ExceptionLifecycle.Status.valueOf(e.get("status").asString()),
                    ExceptionLifecycle.Action.AUTO_MATCH);
            store.update("""
                    update recon_exception set status = 'RESOLVED', resolution_note = ?, resolved_at = now(),
                           updated_by = ? where id = ?""", note, TenantContext.principal(), id);
            outbox.publish("ledger.recon_exception.resolved", "recon_exception", id, store.require("recon_exception", id));
        }
        return open.size();
    }

    /** Files whose records are all actively matched become MATCHED; files with a record back in the queue PARSED. */
    void refreshFileStatus(Collection<UUID> externalRecordIds) {
        List<UUID> fileIds = store.ids("select distinct recon_file_id from recon_record where id = any(?)",
                (Object) externalRecordIds.toArray(UUID[]::new));
        Set<UUID> matched = store.matchedExternalIds();
        for (UUID fileId : fileIds) {
            List<UUID> records = store.ids("select id from recon_record where recon_file_id = ?", fileId);
            boolean all = !records.isEmpty() && matched.containsAll(records);
            store.update("""
                    update recon_file set status = ? where id = ? and status in ('PARSED', 'MATCHED') and status <> ?""",
                    all ? "MATCHED" : "PARSED", fileId, all ? "MATCHED" : "PARSED");
        }
    }

    /** Stored form of an id list: the ids sorted, so equal sets compare equal (compensating records). */
    private ArrayNode sortedArray(Collection<UUID> ids) {
        ArrayNode a = json.createArrayNode();
        new TreeSet<>(ids).forEach(id -> a.add(id.toString()));
        return a;
    }

    private static List<UUID> uuids(JsonNode arr, String field) {
        if (!arr.isArray() || arr.isEmpty()) {
            throw Fields.invalid(field, "required", field + " must be a non-empty array of ids");
        }
        List<UUID> out = new ArrayList<>();
        for (JsonNode n : arr) {
            try {
                out.add(UUID.fromString(n.asString()));
            } catch (IllegalArgumentException e) {
                throw Fields.invalid(field, "invalid_uuid", field + " must contain UUIDs");
            }
        }
        if (new TreeSet<>(out).size() != out.size()) {
            throw Fields.invalid(field, "duplicate", field + " contains duplicates");
        }
        return out;
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String f : fields) {
            if (from.hasNonNull(f)) {
                to.set(f, from.get(f));
            }
        }
    }
}
