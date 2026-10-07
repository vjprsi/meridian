package io.meridian.gl.application;

import io.meridian.approvals.ApprovalSpec;
import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.id.Ids;
import io.meridian.events.OutboxWriter;
import io.meridian.gl.domain.JournalLine;
import io.meridian.gl.domain.JournalRules;
import io.meridian.gl.domain.PeriodStatus;
import io.meridian.gl.infrastructure.GlStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * GL journals. Manual journals (adjustments, accruals, ...) are created by a maker with their lines, balance per
 * currency and only hit the GL balances when a different principal approves them (an append-only
 * gl_journal_approval). System journals (ledger aggregation, provisions, year-end close) post immediately;
 * provisions carry a system approval. Nothing posts into a period that is soft-closed or closed.
 */
@Service
public class JournalService {
    public static final String AUTO_AGGREGATE = "AUTO_AGGREGATE";
    public static final String YEAR_END_CLOSE = "YEAR_END_CLOSE";
    static final Set<String> TYPES = Set.of(AUTO_AGGREGATE, "MANUAL_ADJUSTMENT", "ACCRUAL", "PROVISION", "INTER_ENTITY",
            "REVALUATION", YEAR_END_CLOSE);
    /** Journal types only the system creates; they need no checker approval. */
    public static final Set<String> SYSTEM_TYPES = Set.of(AUTO_AGGREGATE, YEAR_END_CLOSE);
    static final Set<String> BALANCE_SHEET = Set.of("ASSET", "LIABILITY", "EQUITY", "CONTINGENT");

    private final GlStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final Approvals approvals;

    /** Approval resource type for manual GL journals in workflow-service (G11). */
    public static final String APPROVAL_RESOURCE = "gl_journal";

    public JournalService(GlStore store, CrudEngine crud, OutboxWriter outbox, JsonMapper json, Approvals approvals) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
        this.approvals = approvals;
    }

    /** Maker step: records a balanced manual journal awaiting approval (request = GlJournalCreate with lines). */
    @Transactional
    public String createManual(JsonNode req) {
        UUID le = Fields.requireUuid(req, "legal_entity_id");
        UUID periodId = Fields.requireUuid(req, "accounting_period_id");
        String type = Fields.requireText(req, "journal_type");
        LocalDate valueDate = Fields.requireDate(req, "value_date");
        if (!TYPES.contains(type)) {
            throw Fields.invalid("journal_type", "invalid_enum", "journal_type must be one of " + TYPES);
        }
        if (SYSTEM_TYPES.contains(type)) {
            throw ApiException.unprocessable("journal_type_reserved",
                    type + " journals are produced by the system (ledger aggregation, year-end close) only");
        }
        JsonNode period = store.row("select row_to_json(t)::text from accounting_period t where t.id = ? for share", periodId)
                .orElseThrow(() -> ApiException.unprocessable("period_not_found", "Accounting period " + periodId + " does not exist"));
        requirePostable(period, le, valueDate);
        List<JournalLine> lines = parseLines(req.path("lines"));
        JournalRules.validate(lines);
        validateAccounts(le, lines);

        ObjectNode v = crud.toColumns("gl_journal", req);
        String row = insert(v, lines);
        requestApproval(json.readTree(row));
        return withLines(row);
    }

    /**
     * Central maker-checker: the checker decides in the workflow inbox and GlJournalApprovalHandler applies it. The
     * payload carries the per-currency totals and a checksum of the lines (fingerprint); single-currency journals
     * carry their amount so approval policies can route by threshold.
     */
    private void requestApproval(JsonNode journal) {
        if (!approvals.centralised()) {
            return;
        }
        UUID id = UUID.fromString(journal.get("id").asString());
        List<JournalLine> lines = loadLines(id);
        Map<String, BigDecimal> debits = new java.util.TreeMap<>();
        for (JournalLine l : lines) {
            if (l.direction() == JournalLine.Direction.DEBIT) {
                debits.merge(l.currency(), l.amount(), BigDecimal::add);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("legal_entity_id", Fields.text(journal, "legal_entity_id"));
        payload.put("accounting_period_id", Fields.text(journal, "accounting_period_id"));
        payload.put("journal_type", Fields.text(journal, "journal_type"));
        payload.put("value_date", Fields.text(journal, "value_date"));
        payload.put("description", Fields.text(journal, "description"));
        payload.put("line_count", lines.size());
        Map<String, String> totals = new LinkedHashMap<>();
        debits.forEach((ccy, sum) -> totals.put(ccy, sum.toPlainString()));
        payload.put("debit_totals", totals);
        payload.put("checksum", checksum(lines));
        ApprovalSpec spec = ApprovalSpec.of(APPROVAL_RESOURCE, id, "approve",
                "GL journal " + Fields.text(journal, "journal_type") + " " + Fields.text(journal, "value_date")
                        + (debits.size() == 1 ? " " + debits.values().iterator().next().toPlainString() + " "
                        + debits.keySet().iterator().next() : ""), payload);
        if (debits.size() == 1) {
            var only = debits.entrySet().iterator().next();
            spec = spec.withAmount(only.getValue(), only.getKey());
        }
        approvals.request(spec, "journal:" + id);
    }

    /** True while a manual journal awaits its (single) approval with the submitted lines (checksum) unchanged. */
    @Transactional(readOnly = true)
    public boolean awaitsApproval(UUID id, String checksum) {
        return store.find("gl_journal", id)
                .filter(j -> !SYSTEM_TYPES.contains(j.path("journal_type").asString()))
                .filter(j -> store.row("select row_to_json(a)::text from gl_journal_approval a where a.gl_journal_id = ?",
                        id).isEmpty())
                .filter(j -> rejection(id).isEmpty())
                .filter(j -> checksum == null || checksum.equals(checksum(loadLines(id))))
                .isPresent();
    }

    private static String checksum(List<JournalLine> lines) {
        StringBuilder sb = new StringBuilder();
        for (JournalLine l : lines) {
            sb.append(l.glAccountId()).append('|').append(l.direction()).append('|')
                    .append(l.amount().stripTrailingZeros().toPlainString()).append('|').append(l.currency()).append('|')
                    .append(l.baseAmount().stripTrailingZeros().toPlainString()).append('\n');
        }
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Checker step: four-eyes approval posts the journal to the GL balances. The approval is its own append-only
     * record (gl_journal_approval); a journal can be approved once.
     */
    @Transactional
    public String approve(UUID id, String comment) {
        JsonNode j = store.requireLocked("gl_journal", id);
        if (SYSTEM_TYPES.contains(j.get("journal_type").asString())) {
            throw ApiException.conflict("not_approvable", "System-generated journals are posted without approval");
        }
        store.row("select row_to_json(a)::text from gl_journal_approval a where a.gl_journal_id = ?", id)
                .ifPresent(a -> {
                    throw ApiException.conflict("already_approved", "Journal " + id + " was already approved by "
                            + a.get("approved_by").asString());
                });
        rejection(id).ifPresent(r -> {
            throw ApiException.conflict("journal_rejected", "Journal " + id + " was rejected by "
                    + r.get("rejected_by").asString() + "; post a new journal instead");
        });
        if (TenantContext.principal().equals(Fields.text(j, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The maker of a journal cannot approve it");
        }
        UUID le = UUID.fromString(j.get("legal_entity_id").asString());
        LocalDate valueDate = LocalDate.parse(j.get("value_date").asString());
        List<JournalLine> lines = loadLines(id);
        applyToBalances(le, valueDate, lines);
        JsonNode approval = json.readTree(recordApproval(id, TenantContext.principal(), comment));
        publishPosted(id, store.require("gl_journal", id).toString(), comment);
        outbox.publish("ledger.gl_journal.approved", "gl_journal_approval",
                UUID.fromString(approval.get("id").asString()), approval);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "approved directly");
        return approval.toString();
    }

    /**
     * Checker step: rejects a manual journal (four-eyes). The rejection is its own append-only record
     * (gl_journal_rejection); the journal never posts and no longer blocks the period close. A journal is decided once.
     */
    @Transactional
    public String reject(UUID id, String reason) {
        JsonNode j = store.requireLocked("gl_journal", id);
        if (SYSTEM_TYPES.contains(j.get("journal_type").asString())) {
            throw ApiException.conflict("not_approvable", "System-generated journals are posted without approval");
        }
        store.row("select row_to_json(a)::text from gl_journal_approval a where a.gl_journal_id = ?", id)
                .ifPresent(a -> {
                    throw ApiException.conflict("already_approved", "Journal " + id + " was already approved by "
                            + a.get("approved_by").asString());
                });
        rejection(id).ifPresent(r -> {
            throw ApiException.conflict("already_rejected", "Journal " + id + " was already rejected by "
                    + r.get("rejected_by").asString());
        });
        if (TenantContext.principal().equals(Fields.text(j, "created_by"))) {
            throw ApiException.unprocessable("four_eyes_violation", "The maker of a journal cannot reject it");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "A rejection reason is required");
        }
        ObjectNode r = json.createObjectNode();
        r.put("gl_journal_id", id.toString());
        r.put("rejected_by", TenantContext.principal());
        r.put("reason", reason.length() > 500 ? reason.substring(0, 500) : reason);
        JsonNode rejection = json.readTree(crud.insert("gl_journal_rejection", r));
        outbox.publish("ledger.gl_journal.rejected", "gl_journal_rejection",
                UUID.fromString(rejection.get("id").asString()), rejection);
        approvals.cancelOpen(APPROVAL_RESOURCE, id, null, "rejected directly");
        return rejection.toString();
    }

    private java.util.Optional<JsonNode> rejection(UUID journalId) {
        return store.row("select row_to_json(r)::text from gl_journal_rejection r where r.gl_journal_id = ?", journalId);
    }

    /** The journal row with its lines embedded as {@code lines} (GlJournal API shape). */
    @Transactional(readOnly = true)
    public String withLines(UUID id) {
        return withLines(store.require("gl_journal", id).toString());
    }

    private String withLines(String journalRow) {
        ObjectNode n = (ObjectNode) json.readTree(journalRow);
        ArrayNode lines = n.putArray("lines");
        store.rows("select row_to_json(t)::text from gl_journal_line t where t.gl_journal_id = ?::uuid order by t.id",
                n.get("id").asString()).forEach(lines::add);
        return n.toString();
    }

    private String recordApproval(UUID journalId, String approvedBy, String comment) {
        ObjectNode a = json.createObjectNode();
        a.put("gl_journal_id", journalId.toString());
        a.put("approved_by", approvedBy);
        if (comment != null) {
            a.put("comment", comment.length() > 500 ? comment.substring(0, 500) : comment);
        }
        return crud.insert("gl_journal_approval", a);
    }

    /** Posts a system journal (aggregation, provisions) immediately: validated, balanced, applied, published. */
    @Transactional
    public String postSystemJournal(UUID legalEntityId, String type, LocalDate valueDate, String description,
                                    UUID sourceJournalEntryId, String approvedBy, List<JournalLine> lines) {
        JournalRules.validate(lines);
        validateAccounts(legalEntityId, lines);
        List<JsonNode> periods = applyToBalances(legalEntityId, valueDate, lines);
        return insertSystemJournal(legalEntityId, periods.getFirst(), type, valueDate, description, sourceJournalEntryId,
                approvedBy, lines);
    }

    /**
     * Year-end closing journal: posted to the YEAR period only (the closing entries zero the P&L accounts of the
     * year and move the result to retained earnings; finer periods are not touched).
     */
    @Transactional
    public String postClosingJournal(UUID legalEntityId, JsonNode yearPeriod, LocalDate valueDate, String description,
                                     List<JournalLine> lines) {
        JournalRules.validate(lines);
        validateAccounts(legalEntityId, lines);
        applyToPeriods(List.of(yearPeriod), lines);
        return insertSystemJournal(legalEntityId, yearPeriod, YEAR_END_CLOSE, valueDate, description, null, null, lines);
    }

    private String insertSystemJournal(UUID legalEntityId, JsonNode period, String type, LocalDate valueDate,
                                       String description, UUID sourceJournalEntryId, String approvedBy,
                                       List<JournalLine> lines) {
        ObjectNode v = json.createObjectNode();
        v.put("legal_entity_id", legalEntityId.toString());
        v.put("accounting_period_id", period.get("id").asString());
        v.put("journal_type", type);
        v.put("value_date", valueDate.toString());
        if (description != null) {
            v.put("description", description.length() > 500 ? description.substring(0, 500) : description);
        }
        if (sourceJournalEntryId != null) {
            v.put("source_journal_entry_id", sourceJournalEntryId.toString());
        }
        String row = insert(v, lines);
        UUID id = UUID.fromString(json.readTree(row).get("id").asString());
        if (approvedBy != null) {
            recordApproval(id, approvedBy, null);
        }
        publishPosted(id, row, null);
        return row;
    }

    /**
     * Adds the lines to the GL balances of every period of the legal entity that contains the value date
     * (day, month, quarter, year). All of them must accept postings. Returns those periods, finest first.
     */
    List<JsonNode> applyToBalances(UUID legalEntityId, LocalDate valueDate, List<JournalLine> lines) {
        List<JsonNode> periods = store.periodsContaining(legalEntityId, valueDate);
        if (periods.isEmpty()) {
            throw ApiException.unprocessable("no_open_period",
                    "No accounting period of legal entity " + legalEntityId + " covers " + valueDate);
        }
        applyToPeriods(periods, lines);
        return periods;
    }

    /** Adds the lines to the GL balances of exactly these periods; all of them must accept postings. */
    void applyToPeriods(List<JsonNode> periods, List<JournalLine> lines) {
        for (JsonNode p : periods) {
            if (!PeriodStatus.valueOf(p.get("status").asString()).acceptsPostings()) {
                throw ApiException.conflict("period_closed", "Accounting period " + p.get("id").asString() + " ("
                        + p.get("period_type").asString() + " " + p.get("start_date").asString() + ") is "
                        + p.get("status").asString() + "; it does not accept postings");
            }
        }
        record Key(UUID account, String currency) {}
        Map<Key, BigDecimal[]> sums = new LinkedHashMap<>();
        for (JournalLine l : lines) {
            BigDecimal[] s = sums.computeIfAbsent(new Key(l.glAccountId(), l.currency()),
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            if (l.direction() == JournalLine.Direction.DEBIT) {
                s[0] = s[0].add(l.amount());
            } else {
                s[1] = s[1].add(l.amount());
            }
            s[2] = s[2].add(l.signedBase());
        }
        Map<UUID, String> classes = new LinkedHashMap<>();
        for (JsonNode p : periods) {
            sums.forEach((k, s) -> {
                String cls = classes.computeIfAbsent(k.account(),
                        a -> store.require("gl_account", a).get("account_class").asString());
                store.addToBalance(k.account(), p, k.currency(), s[0], s[1], s[2], BALANCE_SHEET.contains(cls));
            });
        }
    }

    void requirePostable(JsonNode period, UUID legalEntityId, LocalDate valueDate) {
        if (!legalEntityId.toString().equals(period.get("legal_entity_id").asString())) {
            throw ApiException.unprocessable("legal_entity_mismatch", "The period belongs to another legal entity");
        }
        if (!PeriodStatus.valueOf(period.get("status").asString()).acceptsPostings()) {
            throw ApiException.conflict("period_closed",
                    "Accounting period is " + period.get("status").asString() + "; it does not accept postings");
        }
        LocalDate start = LocalDate.parse(period.get("start_date").asString());
        LocalDate end = LocalDate.parse(period.get("end_date").asString());
        if (valueDate.isBefore(start) || valueDate.isAfter(end)) {
            throw ApiException.unprocessable("value_date_outside_period",
                    "value_date " + valueDate + " is outside the period " + start + ".." + end);
        }
    }

    Map<UUID, JsonNode> validateAccounts(UUID legalEntityId, List<JournalLine> lines) {
        Map<UUID, JsonNode> accounts = new LinkedHashMap<>();
        for (JournalLine l : lines) {
            JsonNode a = accounts.computeIfAbsent(l.glAccountId(), id -> store.find("gl_account", id).orElseThrow(() ->
                    ApiException.unprocessable("gl_account_not_found", "GL account " + id + " does not exist")));
            if (!legalEntityId.toString().equals(a.get("legal_entity_id").asString())) {
                throw ApiException.unprocessable("legal_entity_mismatch",
                        "GL account " + l.glAccountId() + " belongs to another legal entity");
            }
            if (!a.get("is_postable").asBoolean()) {
                throw ApiException.unprocessable("account_not_postable",
                        "GL account " + a.get("code").asString() + " is a header and does not accept postings");
            }
            if (!"ACTIVE".equals(a.get("status").asString())) {
                throw ApiException.unprocessable("account_inactive", "GL account " + a.get("code").asString() + " is not ACTIVE");
            }
            String restricted = Fields.text(a, "currency");
            if (restricted != null && !restricted.trim().equals(l.currency())) {
                throw ApiException.unprocessable("currency_not_allowed",
                        "GL account " + a.get("code").asString() + " only accepts " + restricted.trim());
            }
        }
        return accounts;
    }

    static List<JournalLine> parseLines(JsonNode arr) {
        List<JournalLine> lines = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return lines;
        }
        for (JsonNode n : arr) {
            BigDecimal amount = Fields.requireDecimal(n, "amount");
            BigDecimal fx = Fields.decimal(n, "fx_rate");
            if (fx != null && fx.signum() <= 0) {
                throw Fields.invalid("fx_rate", "min", "fx_rate must be positive");
            }
            BigDecimal base = fx == null ? amount : amount.multiply(fx).setScale(8, RoundingMode.HALF_EVEN);
            JournalLine.Direction dir = Fields.enumValue(n, "direction", JournalLine.Direction.class);
            if (dir == null) {
                throw Fields.invalid("direction", "required", "Each line needs a direction (DEBIT or CREDIT)");
            }
            lines.add(new JournalLine(Fields.requireUuid(n, "gl_account_id"), dir, amount,
                    Fields.requireText(n, "currency"), base, Fields.text(n, "cost_center"), Fields.uuid(n, "branch_id"),
                    Fields.uuid(n, "product_id")));
        }
        return lines;
    }

    private List<JournalLine> loadLines(UUID journalId) {
        return store.rows("select row_to_json(t)::text from gl_journal_line t where t.gl_journal_id = ? order by t.id",
                        journalId).stream()
                .map(n -> new JournalLine(Fields.uuid(n, "gl_account_id"),
                        JournalLine.Direction.valueOf(n.get("direction").asString()),
                        new BigDecimal(n.get("amount").asString()), n.get("currency").asString().trim(),
                        new BigDecimal(n.get("base_amount").asString()), Fields.text(n, "cost_center"),
                        Fields.uuid(n, "branch_id"), Fields.uuid(n, "product_id")))
                .toList();
    }

    private String insert(ObjectNode journal, List<JournalLine> lines) {
        UUID id = Ids.newId();
        journal.put("id", id.toString());
        String row = crud.insert("gl_journal", journal);
        for (JournalLine l : lines) {
            ObjectNode r = json.createObjectNode();
            r.put("gl_journal_id", id.toString());
            r.put("gl_account_id", l.glAccountId().toString());
            r.put("direction", l.direction().name());
            r.put("amount", l.amount().toPlainString());
            r.put("currency", l.currency());
            r.put("base_amount", l.baseAmount().toPlainString());
            r.put("fx_rate", l.baseAmount().divide(l.amount(), 10, RoundingMode.HALF_EVEN).toPlainString());
            if (l.costCenter() != null) {
                r.put("cost_center", l.costCenter());
            }
            if (l.branchId() != null) {
                r.put("branch_id", l.branchId().toString());
            }
            if (l.productId() != null) {
                r.put("product_id", l.productId().toString());
            }
            crud.insert("gl_journal_line", r);
        }
        return row;
    }

    private void publishPosted(UUID id, String row, String comment) {
        ObjectNode payload = (ObjectNode) json.readTree(row);
        ArrayNode lines = payload.putArray("lines");
        store.rows("select row_to_json(t)::text from gl_journal_line t where t.gl_journal_id = ? order by t.id", id)
                .forEach(lines::add);
        if (comment != null) {
            payload.put("approval_comment", comment);
        }
        outbox.publish("ledger.gl_journal.posted", "gl_journal", id, payload);
    }
}
