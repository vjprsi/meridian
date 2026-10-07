package io.meridian.gl.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** SQL beyond generic CRUD for the GL. All values are bind parameters; table names come from a fixed set. */
@Repository
public class GlStore {
    private static final Set<String> TABLES = Set.of("gl_account", "gl_mapping_rule", "accounting_period", "gl_balance",
            "gl_journal", "gl_journal_line", "gl_journal_approval", "gl_journal_rejection", "period_close_run",
            "ecl_provision", "erp_export");

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public GlStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<JsonNode> find(String table, UUID id) {
        return row("select row_to_json(t)::text from " + table(table) + " t where t.id = ?", id);
    }

    /** Reads a row and takes a row lock until the end of the transaction. */
    public Optional<JsonNode> lock(String table, UUID id) {
        return row("select row_to_json(t)::text from " + table(table) + " t where t.id = ? for update", id);
    }

    public JsonNode require(String table, UUID id) {
        return find(table, id).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public JsonNode requireLocked(String table, UUID id) {
        return lock(table, id).orElseThrow(() -> ApiException.notFound(table.replace('_', ' '), id));
    }

    public Optional<JsonNode> row(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(String.class).optional().map(json::readTree);
    }

    public List<JsonNode> rows(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(String.class).list().stream().map(json::readTree).toList();
    }

    public long count(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(Long.class).single();
    }

    public int update(String sql, Object... args) {
        return jdbc.sql(sql).params(args).update();
    }

    /** Transaction-scoped advisory lock serialising writers on one logical key (e.g. a period calendar). */
    public void advisoryLock(String key) {
        jdbc.sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) x").param(key)
                .query(Integer.class).list();
    }

    /** Periods of a legal entity that contain the date, finest grain (shortest) first; share-locked. */
    public List<JsonNode> periodsContaining(UUID legalEntityId, LocalDate date) {
        return rows("""
                select row_to_json(t)::text from accounting_period t
                where t.legal_entity_id = ? and t.start_date <= ? and t.end_date >= ?
                order by (t.end_date - t.start_date), t.start_date for share""", legalEntityId, date, date);
    }

    /**
     * Adds a movement to the GL balance of one account / period / currency (upsert). Closing balance is
     * debit-positive: opening + debits - credits. A new balance row of a balance-sheet account opens with the
     * closing balance of the same account in the immediately preceding period of the same type.
     */
    public void addToBalance(UUID glAccountId, JsonNode period, String currency, BigDecimal debits, BigDecimal credits,
                             BigDecimal signedBase, boolean balanceSheet) {
        BigDecimal opening = BigDecimal.ZERO;
        BigDecimal openingBase = BigDecimal.ZERO;
        if (balanceSheet) {
            Optional<JsonNode> prev = row("""
                    select row_to_json(b)::text from gl_balance b join accounting_period p on p.id = b.accounting_period_id
                    where b.gl_account_id = ? and b.currency = ? and p.legal_entity_id = ?::uuid and p.period_type = ?
                      and p.end_date = ?::date - 1""",
                    glAccountId, currency, period.get("legal_entity_id").asString(), period.get("period_type").asString(),
                    LocalDate.parse(period.get("start_date").asString()));
            if (prev.isPresent()) {
                opening = new BigDecimal(prev.get().get("closing_balance").asString());
                openingBase = new BigDecimal(prev.get().get("base_closing_balance").asString());
            }
        }
        BigDecimal net = debits.subtract(credits);
        jdbc.sql("""
                insert into gl_balance (tenant_id, created_by, updated_by, gl_account_id, accounting_period_id, currency,
                                        opening_balance, debits, credits, closing_balance, base_closing_balance, last_event_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (gl_account_id, accounting_period_id, currency) do update set
                    debits = gl_balance.debits + excluded.debits,
                    credits = gl_balance.credits + excluded.credits,
                    closing_balance = gl_balance.closing_balance + ?,
                    base_closing_balance = gl_balance.base_closing_balance + ?,
                    last_event_at = now(),
                    updated_by = excluded.updated_by""")
                .params(TenantContext.require(), TenantContext.principal(), TenantContext.principal(), glAccountId,
                        UUID.fromString(period.get("id").asString()), currency, opening, debits, credits,
                        opening.add(net), openingBase.add(signedBase), net, signedBase)
                .update();
    }

    private static String table(String t) {
        if (!TABLES.contains(t)) {
            throw new IllegalArgumentException("unknown table " + t);
        }
        return t;
    }
}
