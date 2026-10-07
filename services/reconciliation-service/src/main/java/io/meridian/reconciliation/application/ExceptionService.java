package io.meridian.reconciliation.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.reconciliation.domain.ExceptionLifecycle;
import io.meridian.reconciliation.infrastructure.ReconStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reconciliation breaks: assignment to an investigator, resolution with a note, accepting the suggested
 * counterpart as a match. Postings and write-offs go through {@link AdjustmentService} (four-eyes). Status only
 * changes through these actions.
 */
@Service
public class ExceptionService {
    private static final String T = "recon_exception";
    private static final Set<String> PATCHABLE = Set.of("assigned_to", "resolution_note");

    private final ReconStore store;
    private final CrudEngine crud;
    private final MatchService matches;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public ExceptionService(ReconStore store, CrudEngine crud, MatchService matches, OutboxWriter outbox,
                            JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.matches = matches;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String assign(UUID id, String assignee) {
        if (assignee == null || assignee.isBlank()) {
            throw Fields.invalid("assigned_to", "required", "assigned_to is required");
        }
        JsonNode e = store.requireLocked(T, id);
        ExceptionLifecycle.Status to = ExceptionLifecycle.next(status(e), ExceptionLifecycle.Action.ASSIGN);
        store.update("update recon_exception set status = ?, assigned_to = ?, updated_by = ? where id = ?",
                to.name(), assignee.trim(), TenantContext.principal(), id);
        return store.require(T, id).toString();
    }

    /**
     * Resolves a break with a note and no posting (explained, e.g. matched outside the system). Postings and
     * write-offs go through {@link AdjustmentService} under four-eyes; a break with a pending adjustment request
     * cannot be resolved directly.
     */
    @Transactional
    public String resolve(UUID id, String note) {
        if (note == null || note.isBlank()) {
            throw Fields.invalid("resolution_note", "required", "resolution_note is required");
        }
        JsonNode e = store.requireLocked(T, id);
        ExceptionLifecycle.Status to = ExceptionLifecycle.next(status(e), ExceptionLifecycle.Action.RESOLVE);
        if (store.count("select count(*) from recon_adjustment where recon_exception_id = ? and status = 'PENDING_APPROVAL'",
                id) > 0) {
            throw ApiException.conflict("adjustment_pending", "Exception " + id
                    + " has an adjustment waiting for approval; approve or reject it first");
        }
        store.update("""
                update recon_exception set status = ?, resolution_note = ?, resolved_at = now(), updated_by = ?
                where id = ?""", to.name(), note.trim(), TenantContext.principal(), id);
        JsonNode row = store.require(T, id);
        outbox.publish("ledger.recon_exception.resolved", T, id, row);
        return row.toString();
    }

    /** Confirms the suggested counterpart as a match confirmed by the caller; the break resolves. */
    @Transactional
    public String acceptSuggestion(UUID id) {
        JsonNode e = store.requireLocked(T, id);
        ExceptionLifecycle.next(status(e), ExceptionLifecycle.Action.ACCEPT_SUGGESTION);
        JsonNode s = e.path("suggested_match");
        boolean pairOfLines = s.path("external_record_ids").size() >= 2; // reversal pair: no ledger side
        if (!s.isObject() || !s.path("internal_entry_ids").isArray()
                || (s.path("internal_entry_ids").isEmpty() && !pairOfLines)
                || !s.path("external_record_ids").isArray() || s.path("external_record_ids").isEmpty()) {
            throw ApiException.conflict("no_suggestion", "Exception " + id + " has no suggested match to accept");
        }
        List<UUID> ext = ids(s.get("external_record_ids"));
        List<UUID> in = ids(s.get("internal_entry_ids"));
        matches.lockMatching();
        matches.requireUnmatched(ext, in);
        String row = matches.insertMatch(UUID.fromString(e.get("recon_run_id").asString()),
                Fields.uuid(s, "recon_rule_id"), Fields.text(s, "match_type") == null ? "ONE_TO_ONE" : s.get("match_type").asString(),
                ext, in, Fields.decimal(s, "difference") == null ? BigDecimal.ZERO : Fields.decimal(s, "difference"),
                Fields.decimal(s, "confidence"), TenantContext.principal(), Fields.uuid(s, "recon_rule_set_id"),
                Fields.text(s, "rule_code"), s.hasNonNull("explanation") ? s.get("explanation") : null);
        String matchId = json.readTree(row).get("id").asString();
        matches.resolveOpenExceptions(ext, in, "Accepted suggested match " + matchId + " by " + TenantContext.principal());
        matches.refreshFileStatus(ext);
        return row;
    }

    /** PATCH: only assignment and notes; status and break facts change through actions or not at all. */
    @Transactional
    public String update(UUID id, String ifMatch, Object body) {
        int version = Fields.ifMatch(ifMatch);
        ObjectNode patch = crud.toColumns(T, body);
        for (String f : patch.propertyNames()) {
            if (!PATCHABLE.contains(f)) {
                throw ApiException.unprocessable("field_not_updatable", "status".equals(f)
                        ? "Status changes go through /assign, /resolve and /accept-suggestion"
                        : f + " cannot be changed on a reconciliation exception");
            }
        }
        JsonNode e = store.requireLocked(T, id);
        if (!ExceptionLifecycle.isOpen(status(e))) {
            throw ApiException.conflict("exception_closed", "Exception " + id + " is " + e.get("status").asString());
        }
        return crud.updateRow(T, Map.of("id", id), patch, version)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match " + version + " does not match the current version; reload and retry"));
    }

    private static ExceptionLifecycle.Status status(JsonNode e) {
        return ExceptionLifecycle.Status.valueOf(e.get("status").asString());
    }

    private static List<UUID> ids(JsonNode arr) {
        List<UUID> out = new ArrayList<>();
        arr.forEach(n -> out.add(UUID.fromString(n.asString())));
        return out;
    }
}
