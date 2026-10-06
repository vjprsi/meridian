package io.meridian.reconciliation.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.reconciliation.domain.Matcher;
import io.meridian.reconciliation.domain.StatementLine;
import io.meridian.reconciliation.infrastructure.LedgerGateway;
import io.meridian.reconciliation.infrastructure.ReconStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A reconciliation run over one source and value-date range: loads the unmatched external records and the source
 * account's ledger entries (from ledger-service), applies the active rules, stores matches, raises breaks
 * (one open break per record at a time), resolves breaks whose records now match, and ages open breaks.
 * The run is recorded RUNNING before ledger-service is called, and ends SUCCEEDED or FAILED.
 */
@Service
public class RunService {
    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    private static final int MAX_RANGE_DAYS = 366;

    private final ReconStore store;
    private final CrudEngine crud;
    private final MatchService matches;
    private final LedgerGateway ledger;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    public RunService(ReconStore store, CrudEngine crud, MatchService matches, LedgerGateway ledger,
                      OutboxWriter outbox, JsonMapper json, TransactionTemplate tx) {
        this.store = store;
        this.crud = crud;
        this.matches = matches;
        this.ledger = ledger;
        this.outbox = outbox;
        this.json = json;
        this.tx = tx;
    }

    public String create(JsonNode req) {
        UUID sourceId = Fields.requireUuid(req, "recon_source_id");
        LocalDate from = Fields.requireDate(req, "from_date");
        LocalDate to = Fields.requireDate(req, "to_date");
        if (to.isBefore(from)) {
            throw ApiException.unprocessable("invalid_date_range", "to_date must not be before from_date");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw ApiException.unprocessable("date_range_too_long", "A run covers at most " + MAX_RANGE_DAYS + " days");
        }
        JsonNode source = store.find("recon_source", sourceId).orElseThrow(() ->
                ApiException.unprocessable("source_not_found", "Reconciliation source " + sourceId + " does not exist"));
        if (!source.get("is_active").asBoolean()) {
            throw ApiException.unprocessable("source_inactive", "Reconciliation source " + sourceId + " is not active");
        }
        UUID account = Fields.uuid(source, "ledger_account_id");
        if (account == null) {
            throw ApiException.unprocessable("source_not_linked",
                    "The source has no ledger_account_id to reconcile against");
        }
        UUID runId = Ids.newId();
        tx.executeWithoutResult(s -> {
            ObjectNode v = json.createObjectNode();
            v.put("id", runId.toString());
            v.put("recon_source_id", sourceId.toString());
            v.put("from_date", from.toString());
            v.put("to_date", to.toString());
            v.put("status", "RUNNING");
            v.put("started_at", OffsetDateTime.now().toString());
            crud.insert("recon_run", v);
        });

        List<Matcher.Rule> rules = rules(sourceId);
        int margin = rules.stream().mapToInt(Matcher.Rule::dateToleranceDays).max().orElse(0);
        List<Matcher.Internal> internal;
        try {
            internal = ledger.entries(account, from.minusDays(margin), to.plusDays(margin));
        } catch (ApiException e) {
            log.warn("Recon run {} failed reading ledger-service: {}", runId, e.getMessage());
            return tx.execute(s -> finish(runId, "FAILED", 0, 0));
        }
        boolean invert = source.path("parser_config").path("invert_direction").asBoolean(false);
        return tx.execute(s -> execute(runId, sourceId, rules, internal, invert, from, to));
    }

    private String execute(UUID runId, UUID sourceId, List<Matcher.Rule> rules, List<Matcher.Internal> internalAll,
                           boolean invert, LocalDate from, LocalDate to) {
        matches.lockMatching();
        Set<UUID> matchedExt = store.matchedExternalIds();
        Set<UUID> matchedInt = store.matchedInternalIds();
        // Records whose break was closed stay out of matching, unless a match was undone for them afterwards.
        Set<UUID> closedExt = new HashSet<>(store.ids("""
                select e.external_record_id from recon_exception e
                where e.status in ('RESOLVED', 'WRITTEN_OFF', 'IGNORED') and e.external_record_id is not null
                  and not exists (select 1 from recon_match c where c.unmatched_at is not null
                        and c.external_record_ids->'ids' @> jsonb_build_array(e.external_record_id::text)
                        and c.created_at > e.resolved_at)"""));
        Set<UUID> closedInt = new HashSet<>(store.ids("""
                select e.internal_entry_id from recon_exception e
                where e.status in ('RESOLVED', 'WRITTEN_OFF', 'IGNORED') and e.internal_entry_id is not null
                  and not exists (select 1 from recon_match c where c.unmatched_at is not null
                        and c.internal_entry_ids->'ids' @> jsonb_build_array(e.internal_entry_id::text)
                        and c.created_at > e.resolved_at)"""));

        List<Matcher.External> externals = new ArrayList<>();
        for (JsonNode r : store.rows("""
                select row_to_json(t)::text from recon_record t
                where t.recon_source_id = ? and t.value_date between ? and ?""", sourceId, from, to)) {
            UUID id = UUID.fromString(r.get("id").asString());
            if (!matchedExt.contains(id) && !closedExt.contains(id)) {
                externals.add(new Matcher.External(id, Fields.text(r, "external_ref"), Fields.text(r, "end_to_end_id"),
                        LocalDate.parse(r.get("value_date").asString()),
                        StatementLine.Direction.valueOf(r.get("direction").asString()),
                        new BigDecimal(r.get("amount").asString()), r.get("currency").asString().trim()));
            }
        }
        List<Matcher.Internal> internals = internalAll.stream()
                .filter(i -> !matchedInt.contains(i.id()) && !closedInt.contains(i.id())).toList();

        Matcher.Outcome outcome = Matcher.run(rules, externals, internals, invert, from, to);

        int matchedRecords = 0;
        for (Matcher.Proposal p : outcome.matches()) {
            String row = matches.insertMatch(runId, p.rule().id(), p.matchType(), p.externalIds(), p.internalIds(),
                    p.difference(), p.confidence(), null);
            matches.resolveOpenExceptions(p.externalIds(), p.internalIds(),
                    "Auto-matched by run " + runId + " (match " + json.readTree(row).get("id").asString() + ")");
            matchedRecords += p.externalIds().size();
        }

        int raised = 0;
        for (Matcher.Break b : outcome.breaks()) {
            boolean exists = b.externalId() != null
                    ? store.count("select count(*) from recon_exception where external_record_id = ? "
                            + "and status in ('OPEN', 'INVESTIGATING')", b.externalId()) > 0
                    : store.count("select count(*) from recon_exception where internal_entry_id = ? "
                            + "and status in ('OPEN', 'INVESTIGATING')", b.internalId()) > 0;
            if (exists) {
                continue;
            }
            ObjectNode v = json.createObjectNode();
            v.put("recon_run_id", runId.toString());
            v.put("reason", b.reason().name());
            v.put("status", "OPEN");
            if (b.externalId() != null) {
                v.put("external_record_id", b.externalId().toString());
            }
            if (b.internalId() != null) {
                v.put("internal_entry_id", b.internalId().toString());
            }
            v.put("amount", b.amount().toPlainString());
            v.put("currency", b.currency());
            if (b.suggestion() != null) {
                v.set("suggested_match", suggestion(b.suggestion()));
            }
            JsonNode row = json.readTree(crud.insert("recon_exception", v));
            outbox.publish("ledger.recon_exception.raised", "recon_exception", UUID.fromString(row.get("id").asString()), row);
            raised++;
        }

        store.update("""
                update recon_exception e set age_days = greatest(0, current_date - e.created_at::date)
                where e.status in ('OPEN', 'INVESTIGATING')
                  and e.recon_run_id in (select id from recon_run where recon_source_id = ?)""", sourceId);
        Set<UUID> touched = new LinkedHashSet<>();
        externals.forEach(e -> touched.add(e.id()));
        if (!touched.isEmpty()) {
            matches.refreshFileStatus(touched);
        }
        return finish(runId, "SUCCEEDED", matchedRecords, raised);
    }

    private String finish(UUID runId, String status, int matched, int exceptions) {
        store.update("""
                update recon_run set status = ?, matched_count = ?, exception_count = ?, finished_at = now(), updated_by = ?
                where id = ?""", status, matched, exceptions, TenantContext.principal(), runId);
        JsonNode row = store.require("recon_run", runId);
        outbox.publish("ledger.recon_run.completed", "recon_run", runId, row);
        return row.toString();
    }

    private ObjectNode suggestion(Matcher.Proposal p) {
        ObjectNode s = json.createObjectNode();
        s.put("match_type", p.matchType());
        ArrayNode ext = s.putArray("external_record_ids");
        p.externalIds().forEach(id -> ext.add(id.toString()));
        ArrayNode in = s.putArray("internal_entry_ids");
        p.internalIds().forEach(id -> in.add(id.toString()));
        s.put("difference", p.difference().toPlainString());
        s.put("confidence", p.confidence().toPlainString());
        if (p.rule() != null) {
            s.put("recon_rule_id", p.rule().id().toString());
        }
        return s;
    }

    private List<Matcher.Rule> rules(UUID sourceId) {
        List<Matcher.Rule> out = new ArrayList<>();
        for (JsonNode r : store.rows("""
                select row_to_json(t)::text from recon_rule t
                where t.is_active and (t.recon_source_id is null or t.recon_source_id = ?)""", sourceId)) {
            Set<String> fields = new HashSet<>();
            r.path("match_fields").path("fields").forEach(f -> fields.add(f.asString()));
            out.add(new Matcher.Rule(UUID.fromString(r.get("id").asString()), r.get("priority").asInt(),
                    r.get("match_type").asString(), fields, new BigDecimal(r.get("amount_tolerance").asString()),
                    r.get("date_tolerance_days").asInt(), r.get("auto_confirm").asBoolean()));
        }
        return out;
    }
}
