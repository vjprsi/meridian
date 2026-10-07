package io.meridian.reconciliation.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.events.OutboxWriter;
import io.meridian.reconciliation.domain.Matcher;
import io.meridian.reconciliation.domain.StatementLine;
import io.meridian.reconciliation.domain.StatementLine.Direction;
import io.meridian.reconciliation.domain.matching.ExternalLine;
import io.meridian.reconciliation.domain.matching.InternalItem;
import io.meridian.reconciliation.domain.matching.MatchRule;
import io.meridian.reconciliation.domain.matching.MatchingEngine;
import io.meridian.reconciliation.domain.matching.RuleSet;
import io.meridian.reconciliation.infrastructure.LedgerGateway;
import io.meridian.reconciliation.infrastructure.ReconStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
 * account's ledger entries (from ledger-service), matches them, stores matches (with rule and explanation), raises
 * breaks (one open break per record at a time) with the best suggestion, resolves breaks whose records now match,
 * ages open breaks and records the run's metrics (external records considered, auto-matched, suggested, auto-match
 * rate, per-rule counts and break reasons).
 *
 * <p>Matching uses the source's PUBLISHED rule set version (else the tenant default version) with the
 * {@link MatchingEngine}; without a published version the legacy recon_rule rows and {@link Matcher} apply.
 * The run is recorded RUNNING before ledger-service is called, and ends SUCCEEDED or FAILED.
 */
@Service
public class RunService {
    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    private static final int MAX_RANGE_DAYS = 366;

    private final ReconStore store;
    private final CrudEngine crud;
    private final MatchService matches;
    private final RuleSetService ruleSets;
    private final LedgerGateway ledger;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    public RunService(ReconStore store, CrudEngine crud, MatchService matches, RuleSetService ruleSets,
                      LedgerGateway ledger, OutboxWriter outbox, JsonMapper json, TransactionTemplate tx) {
        this.store = store;
        this.crud = crud;
        this.matches = matches;
        this.ruleSets = ruleSets;
        this.ledger = ledger;
        this.outbox = outbox;
        this.json = json;
        this.tx = tx;
    }

    /** One match to store, from either engine. */
    private record Link(UUID ruleId, UUID ruleSetId, String ruleCode, String type, List<UUID> ext, List<UUID> in,
                        BigDecimal difference, BigDecimal confidence, JsonNode explanation) {}

    /** One break to raise. */
    private record Brk(String reason, UUID ext, UUID in, BigDecimal amount, String currency, ObjectNode suggestion) {}

    private record Plan(List<Link> links, List<Brk> breaks, ObjectNode metrics, UUID ruleSetId, int considered,
                        int suggested) {}

    public String create(JsonNode req) {
        UUID sourceId = Fields.requireUuid(req, "recon_source_id");
        LocalDate from = Fields.requireDate(req, "from_date");
        LocalDate to = Fields.requireDate(req, "to_date");
        JsonNode source = requireRunnableSource(sourceId);
        checkRange(from, to);
        return run(source, from, to, null);
    }

    /** Validates that a source can be reconciled (exists, active, linked to a ledger account). */
    JsonNode requireRunnableSource(UUID sourceId) {
        JsonNode source = store.find("recon_source", sourceId).orElseThrow(() ->
                ApiException.unprocessable("source_not_found", "Reconciliation source " + sourceId + " does not exist"));
        if (!source.get("is_active").asBoolean()) {
            throw ApiException.unprocessable("source_inactive", "Reconciliation source " + sourceId + " is not active");
        }
        if (Fields.uuid(source, "ledger_account_id") == null) {
            throw ApiException.unprocessable("source_not_linked",
                    "The source has no ledger_account_id to reconcile against");
        }
        return source;
    }

    static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.unprocessable("invalid_date_range", "to_date must not be before from_date");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw ApiException.unprocessable("date_range_too_long", "A run covers at most " + MAX_RANGE_DAYS + " days");
        }
    }

    /** Executes a run and returns the stored recon_run row (SUCCEEDED or FAILED). */
    String run(JsonNode source, LocalDate from, LocalDate to, LocalDate businessDate) {
        UUID sourceId = UUID.fromString(source.get("id").asString());
        UUID account = Fields.uuid(source, "ledger_account_id");
        Optional<RuleSet> ruleSet = ruleSets.published(sourceId);
        List<Matcher.Rule> legacy = ruleSet.isPresent() ? List.of() : legacyRules(sourceId);
        UUID runId = Ids.newId();
        tx.executeWithoutResult(s -> {
            ObjectNode v = json.createObjectNode();
            v.put("id", runId.toString());
            v.put("recon_source_id", sourceId.toString());
            v.put("from_date", from.toString());
            v.put("to_date", to.toString());
            v.put("status", "RUNNING");
            v.put("started_at", Clocks.now().toString());
            if (businessDate != null) {
                v.put("business_date", businessDate.toString());
            }
            ruleSet.ifPresent(rs -> v.put("recon_rule_set_id", rs.id().toString()));
            crud.insert("recon_run", v);
        });
        int margin = ruleSet.map(RunService::margin)
                .orElse(legacy.stream().mapToInt(Matcher.Rule::dateToleranceDays).max().orElse(0));
        List<InternalItem> internal;
        try {
            internal = ledger.items(account, from.minusDays(margin), to.plusDays(margin));
        } catch (ApiException e) {
            log.warn("Recon run {} failed reading ledger-service: {}", runId, e.getMessage());
            return tx.execute(s -> finish(runId, "FAILED", 0, 0, 0, 0, json.createObjectNode().put("error", e.getMessage())));
        }
        boolean invert = source.path("parser_config").path("invert_direction").asBoolean(false);
        return tx.execute(s -> execute(runId, sourceId, ruleSet.orElse(null), legacy, internal, invert, from, to));
    }

    /** Dry run of a rule set version over a source and range: nothing is stored. */
    public Map<String, Object> simulate(UUID ruleSetId, JsonNode req) {
        RuleSet set = ruleSets.load(ruleSetId);
        UUID sourceId = Fields.requireUuid(req, "recon_source_id");
        LocalDate from = Fields.requireDate(req, "from_date");
        LocalDate to = Fields.requireDate(req, "to_date");
        checkRange(from, to);
        JsonNode source = requireRunnableSource(sourceId);
        boolean invert = source.path("parser_config").path("invert_direction").asBoolean(false);
        List<InternalItem> internal = ledger.items(Fields.uuid(source, "ledger_account_id"),
                from.minusDays(margin(set)), to.plusDays(margin(set)));
        return tx.execute(s -> {
            Set<UUID> matchedInt = store.matchedInternalIds();
            List<ExternalLine> externals = openExternals(sourceId, from, to, invert);
            List<InternalItem> internals = internal.stream().filter(i -> !matchedInt.contains(i.id())).toList();
            Plan plan = engine(set, externals, internals, from, to);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("recon_rule_set_id", ruleSetId.toString());
            out.put("version", set.version());
            out.put("recon_source_id", sourceId.toString());
            out.put("from_date", from.toString());
            out.put("to_date", to.toString());
            out.put("metrics", json.treeToValue(plan.metrics(), Map.class));
            List<Map<String, Object>> sample = new ArrayList<>();
            for (Link l : plan.links().subList(0, Math.min(50, plan.links().size()))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rule_code", l.ruleCode());
                m.put("match_type", l.type());
                m.put("external_record_ids", l.ext().stream().map(UUID::toString).toList());
                m.put("internal_entry_ids", l.in().stream().map(UUID::toString).toList());
                m.put("confidence", l.confidence());
                m.put("explanation", json.treeToValue(l.explanation(), Map.class));
                sample.add(m);
            }
            out.put("matches_sample", sample);
            return out;
        });
    }

    private String execute(UUID runId, UUID sourceId, RuleSet set, List<Matcher.Rule> legacy,
                           List<InternalItem> internalAll, boolean invert, LocalDate from, LocalDate to) {
        matches.lockMatching();
        Set<UUID> matchedInt = store.matchedInternalIds();
        Set<UUID> closedInt = closedInternal();
        List<ExternalLine> externals = openExternals(sourceId, from, to, invert);
        List<InternalItem> internals = internalAll.stream()
                .filter(i -> !matchedInt.contains(i.id()) && !closedInt.contains(i.id())).toList();
        Plan plan = set != null ? engine(set, externals, internals, from, to) : legacy(legacy, externals, internals, invert, from, to);

        int matchedRecords = 0;
        for (Link l : plan.links()) {
            String row = matches.insertMatch(runId, l.ruleId(), l.type(), l.ext(), l.in(), l.difference(), l.confidence(),
                    null, l.ruleSetId(), l.ruleCode(), l.explanation());
            matches.resolveOpenExceptions(l.ext(), l.in(),
                    "Auto-matched by run " + runId + " (match " + json.readTree(row).get("id").asString()
                            + (l.ruleCode() == null ? "" : ", rule " + l.ruleCode()) + ")");
            matchedRecords += l.ext().size();
        }

        int raised = 0;
        for (Brk b : plan.breaks()) {
            boolean exists = b.ext() != null
                    ? store.count("select count(*) from recon_exception where external_record_id = ? "
                            + "and status in ('OPEN', 'INVESTIGATING')", b.ext()) > 0
                    : store.count("select count(*) from recon_exception where internal_entry_id = ? "
                            + "and status in ('OPEN', 'INVESTIGATING')", b.in()) > 0;
            if (exists) {
                if (plan.ruleSetId() != null && b.ext() != null && b.suggestion() != null) {
                    // refresh the suggestion of the open break with this run's best candidate
                    store.update("""
                            update recon_exception set suggested_match = ?::jsonb, reason = ?, updated_by = ?
                            where external_record_id = ? and status in ('OPEN', 'INVESTIGATING')""",
                            b.suggestion().toString(), b.reason(), TenantContext.principal(), b.ext());
                }
                continue;
            }
            ObjectNode v = json.createObjectNode();
            v.put("recon_run_id", runId.toString());
            v.put("reason", b.reason());
            v.put("status", "OPEN");
            if (b.ext() != null) {
                v.put("external_record_id", b.ext().toString());
            }
            if (b.in() != null) {
                v.put("internal_entry_id", b.in().toString());
            }
            v.put("amount", b.amount().toPlainString());
            v.put("currency", b.currency());
            if (b.suggestion() != null) {
                v.set("suggested_match", b.suggestion());
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
        plan.metrics().put("breaks_raised", raised);
        return finish(runId, "SUCCEEDED", matchedRecords, raised, plan.considered(), plan.suggested(), plan.metrics());
    }

    // ------------------------------------------------------------------ engines

    private Plan engine(RuleSet set, List<ExternalLine> externals, List<InternalItem> internals, LocalDate from,
                        LocalDate to) {
        MatchingEngine.Result r = MatchingEngine.run(set, externals, internals, from, to);
        List<Link> links = new ArrayList<>();
        for (MatchingEngine.Proposal p : r.matches()) {
            links.add(new Link(null, set.id(), p.ruleCode(), p.matchType(), p.externalIds(), p.internalIds(),
                    p.difference(), confidence(p.confidence()), explanation(set, p)));
        }
        List<Brk> breaks = new ArrayList<>();
        Map<String, Integer> reasons = new TreeMap<>();
        for (MatchingEngine.Break b : r.breaks()) {
            reasons.merge(b.reason().name(), 1, Integer::sum);
            ObjectNode s = null;
            if (b.suggestion() != null) {
                MatchingEngine.Proposal p = b.suggestion();
                s = json.createObjectNode();
                s.put("match_type", p.matchType());
                ArrayNode ext = s.putArray("external_record_ids");
                p.externalIds().forEach(id -> ext.add(id.toString()));
                ArrayNode in = s.putArray("internal_entry_ids");
                p.internalIds().forEach(id -> in.add(id.toString()));
                s.put("difference", p.difference().toPlainString());
                s.put("confidence", confidence(p.confidence()).toPlainString());
                s.put("rule_code", p.ruleCode());
                if (set.id() != null) {
                    s.put("recon_rule_set_id", set.id().toString());
                }
                s.put("ambiguous", p.ambiguous());
                s.set("explanation", explanation(set, p));
            }
            breaks.add(new Brk(b.reason().name(), b.externalId(), b.internalId(), b.amount(), b.currency(), s));
        }
        ObjectNode m = json.createObjectNode();
        m.put("engine", "rule_set");
        if (set.id() != null) {
            m.put("recon_rule_set_id", set.id().toString());
        }
        m.put("rule_set_version", set.version());
        m.put("external_count", r.externalCount());
        m.put("internal_count", r.internalCount());
        m.put("auto_matched_external", r.autoMatchedExternal());
        m.put("suggested_external", r.suggested());
        m.put("duplicates", r.duplicates());
        m.put("auto_match_rate", rate(r.autoMatchedExternal(), r.externalCount()));
        m.set("rules", json.valueToTree(r.ruleStats().values()));
        m.set("break_reasons", json.valueToTree(reasons));
        return new Plan(links, breaks, m, set.id(), r.externalCount(), (int) r.suggested());
    }

    private Plan legacy(List<Matcher.Rule> rules, List<ExternalLine> externals, List<InternalItem> internals,
                        boolean invert, LocalDate from, LocalDate to) {
        // the legacy matcher inverts directions itself: hand it the lines as stored
        List<Matcher.External> ext = externals.stream().map(e -> new Matcher.External(e.id(), e.bankRef(), e.endToEndId(),
                e.valueDate(), invert ? flip(e.direction()) : e.direction(), e.amount(), e.currency())).toList();
        List<Matcher.Internal> in = internals.stream().map(i -> new Matcher.Internal(i.id(), i.reference(), i.valueDate(),
                i.direction(), i.amount(), i.currency())).toList();
        Matcher.Outcome o = Matcher.run(rules, ext, in, invert, from, to);
        List<Link> links = new ArrayList<>();
        Map<String, Integer> perRule = new TreeMap<>();
        for (Matcher.Proposal p : o.matches()) {
            ObjectNode ex = json.createObjectNode();
            ex.put("rule", p.rule().id().toString());
            ex.put("engine", "legacy_rules");
            ex.put("match_fields", String.join(",", new java.util.TreeSet<>(p.rule().fields())));
            ex.put("score", p.confidence().toPlainString());
            links.add(new Link(p.rule().id(), null, null, p.matchType(), p.externalIds(), p.internalIds(), p.difference(),
                    p.confidence(), ex));
            perRule.merge(p.rule().id().toString(), p.externalIds().size(), Integer::sum);
        }
        List<Brk> breaks = new ArrayList<>();
        Map<String, Integer> reasons = new TreeMap<>();
        int suggested = 0;
        for (Matcher.Break b : o.breaks()) {
            reasons.merge(b.reason().name(), 1, Integer::sum);
            ObjectNode s = b.suggestion() == null ? null : legacySuggestion(b.suggestion());
            if (s != null && b.externalId() != null) {
                suggested++;
            }
            breaks.add(new Brk(b.reason().name(), b.externalId(), b.internalId(), b.amount(), b.currency(), s));
        }
        long auto = o.matches().stream().mapToLong(p -> p.externalIds().size()).sum();
        ObjectNode m = json.createObjectNode();
        m.put("engine", "legacy_rules");
        m.put("external_count", ext.size());
        m.put("internal_count", in.size());
        m.put("auto_matched_external", auto);
        m.put("suggested_external", suggested);
        m.put("auto_match_rate", rate(auto, ext.size()));
        m.set("rules", json.valueToTree(perRule));
        m.set("break_reasons", json.valueToTree(reasons));
        return new Plan(links, breaks, m, null, ext.size(), suggested);
    }

    private ObjectNode legacySuggestion(Matcher.Proposal p) {
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

    // ------------------------------------------------------------------ loading

    /** External records of the source in the range that are neither matched nor closed by a resolved break. */
    private List<ExternalLine> openExternals(UUID sourceId, LocalDate from, LocalDate to, boolean invert) {
        Set<UUID> matchedExt = store.matchedExternalIds();
        // Records whose break was closed stay out of matching, unless a match was undone for them afterwards.
        Set<UUID> closedExt = new HashSet<>(store.ids("""
                select e.external_record_id from recon_exception e
                where e.status in ('RESOLVED', 'WRITTEN_OFF', 'IGNORED') and e.external_record_id is not null
                  and not exists (select 1 from recon_match c where c.unmatched_at is not null
                        and c.external_record_ids @> jsonb_build_array(e.external_record_id::text)
                        and c.created_at > e.resolved_at)"""));
        List<ExternalLine> out = new ArrayList<>();
        for (JsonNode r : store.rows("""
                select row_to_json(t)::text from recon_record t
                where t.recon_source_id = ? and t.value_date between ? and ?""", sourceId, from, to)) {
            UUID id = UUID.fromString(r.get("id").asString());
            if (matchedExt.contains(id) || closedExt.contains(id)) {
                continue;
            }
            Direction d = Direction.valueOf(r.get("direction").asString());
            out.add(new ExternalLine(id, LocalDate.parse(r.get("value_date").asString()), invert ? flip(d) : d,
                    new BigDecimal(r.get("amount").asString()), r.get("currency").asString().trim(),
                    Fields.text(r, "external_ref"), Fields.text(r, "end_to_end_id"), Fields.text(r, "instruction_id"),
                    Fields.text(r, "uetr"), Fields.text(r.path("raw"), "batch_ref"), Fields.text(r, "counterparty"),
                    Fields.text(r, "counterparty_account"), Fields.text(r, "narrative"),
                    r.path("is_reversal").asBoolean(false), Fields.decimal(r, "charges_amount"),
                    Fields.decimal(r, "original_amount"),
                    Fields.text(r, "original_currency") == null ? null : r.get("original_currency").asString().trim()));
        }
        return out;
    }

    private Set<UUID> closedInternal() {
        return new HashSet<>(store.ids("""
                select e.internal_entry_id from recon_exception e
                where e.status in ('RESOLVED', 'WRITTEN_OFF', 'IGNORED') and e.internal_entry_id is not null
                  and not exists (select 1 from recon_match c where c.unmatched_at is not null
                        and c.internal_entry_ids @> jsonb_build_array(e.internal_entry_id::text)
                        and c.created_at > e.resolved_at)"""));
    }

    private List<Matcher.Rule> legacyRules(UUID sourceId) {
        List<Matcher.Rule> out = new ArrayList<>();
        for (JsonNode r : store.rows("""
                select row_to_json(t)::text from recon_rule t
                where t.is_active and (t.recon_source_id is null or t.recon_source_id = ?)""", sourceId)) {
            Set<String> fields = new HashSet<>();
            r.path("match_fields").forEach(f -> fields.add(f.asString()));
            out.add(new Matcher.Rule(UUID.fromString(r.get("id").asString()), r.get("priority").asInt(),
                    r.get("match_type").asString(), fields, new BigDecimal(r.get("amount_tolerance").asString()),
                    r.get("date_tolerance_days").asInt(), r.get("auto_confirm").asBoolean()));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private String finish(UUID runId, String status, int matched, int exceptions, int considered, int suggested,
                          ObjectNode metrics) {
        BigDecimal rate = considered == 0 ? null
                : BigDecimal.valueOf(matched).divide(BigDecimal.valueOf(considered), 6, RoundingMode.HALF_EVEN);
        store.update("""
                update recon_run set status = ?, matched_count = ?, exception_count = ?, finished_at = ?::timestamptz,
                       external_count = ?, suggested_count = ?, auto_match_rate = ?, metrics = ?::jsonb, updated_by = ?
                where id = ?""", status, matched, exceptions, Clocks.now().toString(), considered, suggested, rate,
                metrics.toString(), TenantContext.principal(), runId);
        JsonNode row = store.require("recon_run", runId);
        outbox.publish("ledger.recon_run.completed", "recon_run", runId, row);
        return row.toString();
    }

    private ObjectNode explanation(RuleSet set, MatchingEngine.Proposal p) {
        ObjectNode ex = json.valueToTree(p.explanation());
        if (set.id() != null) {
            ex.put("recon_rule_set_id", set.id().toString());
        }
        ex.put("rule_set_version", set.version());
        return ex;
    }

    static int margin(RuleSet set) {
        return set.rules().stream().filter(MatchRule::enabled).mapToInt(MatchRule::dateWindowDays).max().orElse(0);
    }

    private static BigDecimal confidence(double c) {
        return BigDecimal.valueOf(c).setScale(4, RoundingMode.HALF_EVEN);
    }

    private static Double rate(long n, long d) {
        return d == 0 ? null : Math.round(n * 1_000_000.0 / d) / 1_000_000.0;
    }

    private static Direction flip(Direction d) {
        return d == StatementLine.Direction.CREDIT ? Direction.DEBIT : Direction.CREDIT;
    }
}
