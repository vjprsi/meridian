package io.meridian.ledger.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.ledger.domain.AccountState;
import io.meridian.ledger.domain.PostingLine;
import io.meridian.ledger.domain.PostingRules;
import io.meridian.ledger.infrastructure.LedgerStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The posting engine: the only code path that changes balances.
 * One call = one atomic, balanced journal entry. Accounts are locked in id order, so concurrent postings
 * never deadlock; per-account versions are gapless; a replay with the same idempotency key returns the
 * original entry instead of posting twice.
 */
@Service
public class PostingService {

    /** A posting request after header/body parsing. */
    public record Posting(UUID ledgerId, String idempotencyKey, String entryType, String description,
                          OffsetDateTime effectiveAt, LocalDate valueDate, String sourceService, String sourceType,
                          UUID sourceId, String correlationId, UUID reversalOfId, JsonNode metadata,
                          JsonNode customFields, List<PostingLine> lines) {}

    /** Result: the entry JSON (JournalEntry API shape) and whether it was a replay. */
    public record Posted(String json, boolean replayed) {}

    private final LedgerStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public PostingService(LedgerStore store, CrudEngine crud, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public Posted post(Posting p) {
        PostingRules.validate(p.lines());
        LedgerStore.LedgerInfo ledger = store.ledger(p.ledgerId());
        var existing = store.journalEntryByKey(p.ledgerId(), p.idempotencyKey());
        if (existing.isPresent()) {
            return new Posted(store.journalEntryJson(existing.get()).orElseThrow(), true);
        }
        if (!"ACTIVE".equals(ledger.status())) {
            throw ApiException.unprocessable("ledger_locked", "Ledger " + ledger.id() + " is " + ledger.status());
        }

        Map<UUID, AccountState> accounts = store.lockAccounts(p.lines().stream().map(PostingLine::accountId).toList());
        accounts.values().forEach(a -> {
            if (!a.ledgerId.equals(ledger.id())) {
                throw ApiException.unprocessable("account_in_other_ledger", "Account " + a.id + " is not in ledger " + ledger.id());
            }
        });

        UUID entryId = Ids.newId();
        OffsetDateTime effectiveAt = p.effectiveAt() != null ? p.effectiveAt() : OffsetDateTime.now();
        LocalDate valueDate = p.valueDate() != null ? p.valueDate() : effectiveAt.toLocalDate();
        BigDecimal totalDebitsBase = BigDecimal.ZERO;
        StringBuilder hashInput = new StringBuilder(entryId + "|" + ledger.id() + "|" + effectiveAt);

        List<ObjectNode> lineRows = new java.util.ArrayList<>();
        int lineNo = 0;
        for (PostingLine line : p.lines()) {
            AccountState account = accounts.get(line.accountId());
            BigDecimal fx = fxRate(line, ledger.baseCurrency());
            BigDecimal base = line.amount().multiply(fx).setScale(8, RoundingMode.HALF_EVEN);
            BigDecimal balanceAfter = account.apply(line);
            if (line.direction() == PostingLine.Direction.DEBIT) {
                totalDebitsBase = totalDebitsBase.add(base);
            }
            ObjectNode row = json.createObjectNode();
            row.put("journal_entry_id", entryId.toString());
            row.put("ledger_account_id", line.accountId().toString());
            row.put("line_no", ++lineNo);
            row.put("direction", line.direction().name());
            row.put("amount", line.amount().toPlainString());
            row.put("currency", line.currency());
            row.put("fx_rate", fx.toPlainString());
            row.put("base_amount", base.toPlainString());
            row.put("effective_at", effectiveAt.toString());
            row.put("account_version", account.version);
            row.put("balance_after", balanceAfter.toPlainString());
            if (line.description() != null) {
                row.put("description", line.description());
            }
            lineRows.add(row);
            hashInput.append('|').append(line.accountId()).append(line.direction()).append(line.amount().toPlainString())
                    .append(line.currency()).append(account.version);
        }

        ObjectNode entry = json.createObjectNode();
        entry.put("id", entryId.toString());
        entry.put("ledger_id", ledger.id().toString());
        entry.put("idempotency_key", p.idempotencyKey());
        entry.put("entry_type", p.entryType() == null ? "STANDARD" : p.entryType());
        entry.put("effective_at", effectiveAt.toString());
        entry.put("value_date", valueDate.toString());
        entry.put("source_service", p.sourceService());
        entry.put("entry_count", p.lines().size());
        entry.put("total_debits", totalDebitsBase.toPlainString());
        entry.put("hash", sha256(hashInput.toString()));
        putIfPresent(entry, "description", p.description());
        putIfPresent(entry, "source_type", p.sourceType());
        putIfPresent(entry, "source_id", p.sourceId());
        putIfPresent(entry, "correlation_id", p.correlationId());
        putIfPresent(entry, "reversal_of_id", p.reversalOfId());
        if (p.metadata() != null && !p.metadata().isNull()) {
            entry.set("metadata", p.metadata());
        }
        if (p.customFields() != null) {
            p.customFields().propertyNames().forEach(n -> entry.set(n, p.customFields().get(n)));
        }

        try {
            insertWithId("journal_entry", entry);
        } catch (DuplicateKeyException race) {
            // Another request with the same idempotency key committed first: return its result.
            UUID winner = store.journalEntryByKey(p.ledgerId(), p.idempotencyKey()).orElseThrow(() -> race);
            return new Posted(store.journalEntryJson(winner).orElseThrow(), true);
        }
        lineRows.forEach(r -> crud.insert("ledger_entry", r));
        accounts.values().stream().filter(a -> a.changed).forEach(store::saveBalance);

        String posted = store.journalEntryJson(entryId).orElseThrow();
        outbox.publish(p.reversalOfId() == null ? "ledger.journal_entry.posted" : "ledger.journal_entry.reversed",
                "journal_entry", entryId, json.readTree(posted));
        for (AccountState a : accounts.values()) {
            store.balanceJson(a.id).ifPresent(b ->
                    outbox.publish("ledger.balance.changed", "account_balance", a.id, json.readTree(b)));
        }
        return new Posted(posted, false);
    }

    /** Reverses a posted entry with an exactly mirrored entry. An entry can be reversed only once. */
    @Transactional
    public Posted reverse(UUID journalEntryId, String idempotencyKey, String reason, OffsetDateTime effectiveAt) {
        JsonNode original = json.readTree(store.journalEntryJson(journalEntryId)
                .orElseThrow(() -> ApiException.notFound("journal entry", journalEntryId)));
        UUID ledgerId = UUID.fromString(original.get("ledger_id").asString());
        var replay = store.journalEntryByKey(ledgerId, idempotencyKey);
        if (replay.isPresent()) {
            return new Posted(store.journalEntryJson(replay.get()).orElseThrow(), true);
        }
        if (store.reversalOf(journalEntryId).isPresent()) {
            throw ApiException.conflict("already_reversed", "Journal entry " + journalEntryId + " was already reversed");
        }
        List<PostingLine> lines = new java.util.ArrayList<>();
        for (JsonNode e : original.get("entries")) {
            lines.add(new PostingLine(UUID.fromString(e.get("ledger_account_id").asString()),
                    PostingLine.Direction.valueOf(e.get("direction").asString()),
                    new BigDecimal(e.get("amount").asString()), e.get("currency").asString(),
                    new BigDecimal(e.get("fx_rate").asString()), "Reversal: " + reason).reversed());
        }
        return post(new Posting(ledgerId, idempotencyKey, "REVERSAL", "Reversal of " + journalEntryId + ": " + reason,
                effectiveAt, null, TenantContext.principal(), "JOURNAL_ENTRY", journalEntryId, null, journalEntryId,
                null, null, lines));
    }

    private void insertWithId(String table, ObjectNode values) {
        crud.insert(table, values);
    }

    private static BigDecimal fxRate(PostingLine line, String baseCurrency) {
        if (line.currency().equals(baseCurrency)) {
            if (line.fxRate() != null && line.fxRate().compareTo(BigDecimal.ONE) != 0) {
                throw ApiException.unprocessable("invalid_fx_rate", "fx_rate must be 1 for base-currency lines");
            }
            return BigDecimal.ONE;
        }
        if (line.fxRate() == null || line.fxRate().signum() <= 0) {
            throw ApiException.unprocessable("fx_rate_required",
                    "fx_rate to " + baseCurrency + " is required for " + line.currency() + " lines");
        }
        return line.fxRate();
    }

    private static void putIfPresent(ObjectNode n, String key, Object v) {
        if (v != null) {
            n.put(key, v.toString());
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
