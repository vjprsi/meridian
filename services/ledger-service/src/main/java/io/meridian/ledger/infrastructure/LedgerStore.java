package io.meridian.ledger.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.ledger.domain.AccountState;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL specific to the posting engine. Generic reads and writes go through the CrudEngine. */
@Repository
public class LedgerStore {
    private final JdbcClient jdbc;

    public LedgerStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record LedgerInfo(UUID id, String baseCurrency, String status) {}

    public LedgerInfo ledger(UUID ledgerId) {
        return jdbc.sql("select id, base_currency, status from ledger where id = ?")
                .param(ledgerId)
                .query((rs, i) -> new LedgerInfo(rs.getObject(1, UUID.class), rs.getString(2).trim(), rs.getString(3)))
                .optional()
                .orElseThrow(() -> ApiException.notFound("ledger", ledgerId));
    }

    /**
     * Locks the balance rows of the given accounts in id order (deadlock-free under concurrency) and returns
     * their state keyed by account id. Fails if any account is missing.
     */
    public Map<UUID, AccountState> lockAccounts(Collection<UUID> ids) {
        UUID[] sorted = ids.stream().distinct().sorted().toArray(UUID[]::new);
        List<AccountState> rows = jdbc.sql("""
                select a.id, a.ledger_id, a.currency, a.normal_balance, a.status, a.allow_negative, a.overdraft_limit,
                       a.min_balance, b.posted_balance, b.posted_debits, b.posted_credits, b.pending_debits,
                       b.pending_credits, b.account_version
                from ledger_account a join account_balance b on b.ledger_account_id = a.id
                where a.id = any(?) order by a.id for update of b""")
                .param(sorted)
                .query((rs, i) -> new AccountState(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3).trim(),
                        rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getBigDecimal(7), rs.getBigDecimal(8),
                        rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getBigDecimal(12),
                        rs.getBigDecimal(13), rs.getLong(14)))
                .list();
        Map<UUID, AccountState> byId = new LinkedHashMap<>();
        rows.forEach(r -> byId.put(r.id, r));
        for (UUID id : sorted) {
            if (!byId.containsKey(id)) {
                throw ApiException.unprocessable("account_not_found", "Ledger account " + id + " does not exist");
            }
        }
        return byId;
    }

    public void saveBalance(AccountState a) {
        jdbc.sql("""
                update account_balance set posted_balance = ?, posted_debits = ?, posted_credits = ?,
                       pending_debits = ?, pending_credits = ?, available_balance = ?, account_version = ?,
                       last_entry_at = now()
                where ledger_account_id = ?""")
                .params(a.postedBalance, a.postedDebits, a.postedCredits, a.pendingDebits, a.pendingCredits,
                        a.available(), a.version, a.id)
                .update();
    }

    /** Writes all changed balances in one statement. */
    public void saveBalances(Collection<AccountState> accounts) {
        List<AccountState> changed = accounts.stream().filter(a -> a.changed).toList();
        if (changed.isEmpty()) {
            return;
        }
        jdbc.sql("""
                update account_balance b set posted_balance = u.posted, posted_debits = u.debits,
                       posted_credits = u.credits, pending_debits = u.pdebits, pending_credits = u.pcredits,
                       available_balance = u.available, account_version = u.version, last_entry_at = now()
                from unnest(?::uuid[], ?::numeric[], ?::numeric[], ?::numeric[], ?::numeric[], ?::numeric[],
                            ?::numeric[], ?::bigint[])
                     as u(id, posted, debits, credits, pdebits, pcredits, available, version)
                where b.ledger_account_id = u.id""")
                .params(changed.stream().map(x -> x.id).toArray(UUID[]::new),
                        changed.stream().map(x -> x.postedBalance).toArray(java.math.BigDecimal[]::new),
                        changed.stream().map(x -> x.postedDebits).toArray(java.math.BigDecimal[]::new),
                        changed.stream().map(x -> x.postedCredits).toArray(java.math.BigDecimal[]::new),
                        changed.stream().map(x -> x.pendingDebits).toArray(java.math.BigDecimal[]::new),
                        changed.stream().map(x -> x.pendingCredits).toArray(java.math.BigDecimal[]::new),
                        changed.stream().map(AccountState::available).toArray(java.math.BigDecimal[]::new),
                        changed.stream().map(x -> x.version).toArray(Long[]::new))
                .update();
    }

    /** Current balance rows of several accounts as JSON text. */
    public List<String> balancesJson(Collection<UUID> accountIds) {
        return jdbc.sql("select row_to_json(b)::text from account_balance b where b.ledger_account_id = any(?)")
                .param(accountIds.toArray(UUID[]::new)).query(String.class).list();
    }

    public Optional<UUID> journalEntryByKey(UUID ledgerId, String idempotencyKey) {
        return jdbc.sql("select id from journal_entry where ledger_id = ? and idempotency_key = ?")
                .params(ledgerId, idempotencyKey)
                .query(UUID.class)
                .optional();
    }

    public Optional<UUID> reversalOf(UUID journalEntryId) {
        return jdbc.sql("select id from journal_entry where reversal_of_id = ?")
                .param(journalEntryId).query(UUID.class).optional();
    }

    /** Journal entry row JSON with its lines embedded as "entries" (matches the JournalEntry API model). */
    public Optional<String> journalEntryJson(UUID id) {
        return jdbc.sql("""
                select (row_to_json(j)::jsonb || jsonb_build_object('entries',
                        coalesce((select jsonb_agg(row_to_json(e) order by e.line_no)
                                  from ledger_entry e where e.journal_entry_id = j.id), '[]'::jsonb)))::text
                from journal_entry j where j.id = ?""")
                .param(id).query(String.class).optional();
    }

    public Optional<String> balanceJson(UUID accountId) {
        return jdbc.sql("select row_to_json(b)::text from account_balance b where b.ledger_account_id = ?")
                .param(accountId).query(String.class).optional();
    }
}
