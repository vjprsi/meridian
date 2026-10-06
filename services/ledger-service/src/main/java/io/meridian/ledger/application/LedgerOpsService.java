package io.meridian.ledger.application;

import io.meridian.common.id.Ids;
import io.meridian.events.OutboxWriter;
import io.meridian.ledger.domain.EntryHasher;
import io.meridian.ledger.infrastructure.LedgerStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Ledger operations: integrity verification and end-of-day processing (per tenant, per ledger). */
@Service
public class LedgerOpsService {
    private static final int MAX_FINDINGS = 20;

    private final LedgerStore store;
    private final HoldService holds;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public LedgerOpsService(LedgerStore store, HoldService holds, CrudEngine crud, OutboxWriter outbox,
                            JdbcClient jdbc, JsonMapper json) {
        this.store = store;
        this.holds = holds;
        this.crud = crud;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Recomputes every account balance from its entries (normal-balance terms), checks per-account versions are
     * 1..n without gaps, checks each entry balances per currency, and recomputes each entry's content hash.
     * Read-only on business data; records the result in ledger_verification.
     */
    @Transactional
    public String verify(UUID ledgerId) {
        store.ledger(ledgerId);
        ObjectNode details = json.createObjectNode();

        List<Map<String, Object>> balances = jdbc.sql("""
                select a.id, b.posted_balance,
                       coalesce(sum(case when e.direction = a.normal_balance then e.amount else -e.amount end), 0) as computed,
                       count(e.id) as n, coalesce(max(e.account_version), 0) as max_v,
                       count(distinct e.account_version) as distinct_v
                from ledger_account a
                join account_balance b on b.ledger_account_id = a.id
                left join ledger_entry e on e.ledger_account_id = a.id
                where a.ledger_id = ?
                group by a.id, b.posted_balance""")
                .param(ledgerId).query().listOfRows();
        ArrayNode balanceFindings = details.putArray("balance_mismatches");
        ArrayNode gapFindings = details.putArray("version_gaps");
        int balanceMismatches = 0;
        int versionGaps = 0;
        for (Map<String, Object> r : balances) {
            BigDecimal stored = (BigDecimal) r.get("posted_balance");
            BigDecimal computed = (BigDecimal) r.get("computed");
            if (stored.compareTo(computed) != 0) {
                balanceMismatches++;
                if (balanceFindings.size() < MAX_FINDINGS) {
                    balanceFindings.addObject().put("account_id", r.get("id").toString())
                            .put("stored", stored.toPlainString()).put("computed", computed.toPlainString());
                }
            }
            long n = ((Number) r.get("n")).longValue();
            if (n != ((Number) r.get("max_v")).longValue() || n != ((Number) r.get("distinct_v")).longValue()) {
                versionGaps++;
                if (gapFindings.size() < MAX_FINDINGS) {
                    gapFindings.addObject().put("account_id", r.get("id").toString()).put("entries", n);
                }
            }
        }

        List<Map<String, Object>> unbalanced = jdbc.sql("""
                select j.id, e.currency from journal_entry j join ledger_entry e on e.journal_entry_id = j.id
                where j.ledger_id = ?
                group by j.id, e.currency
                having sum(case when e.direction = 'DEBIT' then e.amount else -e.amount end) <> 0""")
                .param(ledgerId).query().listOfRows();
        ArrayNode unbalancedFindings = details.putArray("unbalanced_entries");
        unbalanced.stream().limit(MAX_FINDINGS).forEach(r ->
                unbalancedFindings.addObject().put("journal_entry_id", r.get("id").toString()).put("currency", (String) r.get("currency")));

        // Content hashes, in booking order, and the chain head.
        Map<UUID, List<EntryHasher.Line>> lines = new HashMap<>();
        jdbc.sql("""
                select e.journal_entry_id, e.ledger_account_id, e.direction, e.amount, e.currency, e.account_version
                from ledger_entry e join journal_entry j on j.id = e.journal_entry_id
                where j.ledger_id = ? order by e.journal_entry_id, e.line_no""")
                .param(ledgerId)
                .query((rs, i) -> {
                    lines.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(new EntryHasher.Line(rs.getObject(2, UUID.class), rs.getString(3), rs.getBigDecimal(4),
                                    rs.getString(5).trim(), rs.getLong(6)));
                    return null;
                }).list();
        record Entry(UUID id, Instant effectiveAt, String hash) {}
        List<Entry> entries = jdbc.sql("""
                select id, effective_at, hash from journal_entry where ledger_id = ? order by booked_at, id""")
                .param(ledgerId)
                .query((rs, i) -> new Entry(rs.getObject(1, UUID.class),
                        rs.getObject(2, OffsetDateTime.class).toInstant(), rs.getString(3)))
                .list();
        ArrayNode hashFindings = details.putArray("hash_mismatches");
        int hashMismatches = 0;
        String chain = null;
        for (Entry e : entries) {
            String recomputed = EntryHasher.hash(e.id(), ledgerId, e.effectiveAt(), lines.getOrDefault(e.id(), List.of()));
            if (!recomputed.equals(e.hash())) {
                hashMismatches++;
                if (hashFindings.size() < MAX_FINDINGS) {
                    hashFindings.addObject().put("journal_entry_id", e.id().toString());
                }
            }
            chain = EntryHasher.chain(chain, e.hash() == null ? "" : e.hash());
        }

        boolean passed = balanceMismatches == 0 && versionGaps == 0 && unbalanced.isEmpty() && hashMismatches == 0;
        ObjectNode row = json.createObjectNode();
        UUID id = Ids.newId();
        row.put("id", id.toString());
        row.put("ledger_id", ledgerId.toString());
        row.put("accounts_checked", balances.size());
        row.put("entries_checked", entries.size());
        row.put("balance_mismatches", balanceMismatches);
        row.put("version_gaps", versionGaps);
        row.put("unbalanced_entries", unbalanced.size());
        row.put("hash_mismatches", hashMismatches);
        row.put("chain_head", chain == null ? EntryHasher.chain(null, "") : chain);
        row.put("passed", passed);
        row.set("details", details);
        String saved = crud.insert("ledger_verification", row);
        outbox.publish("ledger.ledger.verified", "ledger_verification", id, json.readTree(saved));
        return saved;
    }

    /**
     * End of day for one ledger and business date: releases holds whose expires_at has passed, then writes one
     * balance snapshot per account for the date. Re-running for the same date skips accounts already snapshotted.
     */
    @Transactional
    public String endOfDay(UUID ledgerId, LocalDate businessDate) {
        store.ledger(ledgerId);
        OffsetDateTime started = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime dayStart = businessDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime dayEnd = dayStart.plusDays(1);

        List<UUID> expired = jdbc.sql("""
                select h.id from hold h join ledger_account a on a.id = h.ledger_account_id
                where a.ledger_id = ? and h.status in ('ACTIVE', 'PARTIALLY_CAPTURED') and h.expires_at <= ?""")
                .params(ledgerId, dayEnd).query(UUID.class).list();
        expired.forEach(h -> holds.expire(h, "Expired at end of day " + businessDate));

        List<Map<String, Object>> accounts = jdbc.sql("""
                select a.id, a.currency, b.pending_debits
                from ledger_account a join account_balance b on b.ledger_account_id = a.id
                where a.ledger_id = ?""")
                .param(ledgerId).query().listOfRows();
        int written = 0;
        int skipped = 0;
        for (Map<String, Object> a : accounts) {
            UUID accountId = (UUID) a.get("id");
            boolean exists = jdbc.sql("select 1 from balance_snapshot where ledger_account_id = ? and as_of_date = ?")
                    .params(accountId, businessDate).query(Integer.class).optional().isPresent();
            if (exists) {
                skipped++;
                continue;
            }
            Map<String, Object> day = jdbc.sql("""
                    select coalesce(sum(case when direction = 'DEBIT' then amount end), 0) as debits,
                           coalesce(sum(case when direction = 'CREDIT' then amount end), 0) as credits,
                           (select balance_after from ledger_entry where ledger_account_id = ? and created_at < ?
                            order by account_version desc limit 1) as closing,
                           (select account_version from ledger_entry where ledger_account_id = ? and created_at < ?
                            order by account_version desc limit 1) as version,
                           (select balance_after from ledger_entry where ledger_account_id = ? and created_at < ?
                            order by account_version desc limit 1) as opening
                    from ledger_entry where ledger_account_id = ? and created_at >= ? and created_at < ?""")
                    .params(accountId, dayEnd, accountId, dayEnd, accountId, dayStart, accountId, dayStart, dayEnd)
                    .query().singleRow();
            BigDecimal opening = orZero(day.get("opening"));
            BigDecimal closing = day.get("closing") == null ? opening : (BigDecimal) day.get("closing");
            ObjectNode snap = json.createObjectNode();
            snap.put("ledger_account_id", accountId.toString());
            snap.put("as_of_date", businessDate.toString());
            snap.put("currency", ((String) a.get("currency")).trim());
            snap.put("opening_balance", opening.toPlainString());
            snap.put("total_debits", orZero(day.get("debits")).toPlainString());
            snap.put("total_credits", orZero(day.get("credits")).toPlainString());
            snap.put("closing_balance", closing.toPlainString());
            snap.put("available_balance", closing.subtract(orZero(a.get("pending_debits"))).toPlainString());
            snap.put("account_version", day.get("version") == null ? 0L : ((Number) day.get("version")).longValue());
            crud.insert("balance_snapshot", snap);
            written++;
        }

        ObjectNode run = json.createObjectNode();
        UUID id = Ids.newId();
        run.put("id", id.toString());
        run.put("ledger_id", ledgerId.toString());
        run.put("business_date", businessDate.toString());
        run.put("status", "SUCCEEDED");
        run.put("holds_expired", expired.size());
        run.put("snapshots_written", written);
        run.put("snapshots_skipped", skipped);
        run.put("started_at", started.toString());
        run.put("finished_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        String saved = crud.insert("ledger_eod_run", run);
        outbox.publish("ledger.eod.completed", "ledger_eod_run", id, json.readTree(saved));
        return saved;
    }

    private static BigDecimal orZero(Object v) {
        return v == null ? BigDecimal.ZERO : (BigDecimal) v;
    }
}
