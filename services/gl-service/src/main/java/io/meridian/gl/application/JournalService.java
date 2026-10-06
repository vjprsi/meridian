package io.meridian.gl.application;

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

    public JournalService(GlStore store, CrudEngine crud, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
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
        return withLines(insert(v, lines));
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
        return approval.toString();
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
