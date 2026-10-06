package io.meridian.gl.application;

import io.meridian.common.error.ApiException;
import io.meridian.gl.domain.JournalLine;
import io.meridian.gl.domain.MappingRule;
import io.meridian.gl.infrastructure.GlStore;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Real-time GL aggregation of ledger postings (consumer-free path). Each ledger journal entry becomes exactly one
 * AUTO_AGGREGATE GL journal: every ledger line is mapped to a GL account (the ledger account's own GL control
 * account if it has one, otherwise the GL mapping rules), lines are summed per GL account / direction / currency,
 * and the GL balances of every open period covering the value date are updated. Re-delivery of the same entry is a
 * no-op (idempotent by source_journal_entry_id).
 */
@Service
public class LedgerAggregationService {

    /** One ledger line with the attributes the GL mapping needs. */
    public record PostingLine(UUID ledgerAccountId, String ledgerAccountType, UUID productId, String ownerType,
                              UUID glAccountId, String direction, BigDecimal amount, String currency,
                              BigDecimal baseAmount) {}

    /** A posted ledger journal entry, enriched with the owning legal entity and account attributes. */
    public record LedgerPosting(UUID journalEntryId, UUID legalEntityId, String entryType, LocalDate valueDate,
                                String description, List<PostingLine> lines) {}

    /** Outcome: the GL journal row and whether this delivery was a duplicate. */
    public record Aggregated(String journalJson, boolean duplicate) {}

    private final GlStore store;
    private final JournalService journals;
    private final MappingService mapping;
    private final ServiceClient services;
    private final TransactionTemplate tx;

    public LedgerAggregationService(GlStore store, JournalService journals, MappingService mapping,
                                    ServiceClient services, TransactionTemplate tx) {
        this.store = store;
        this.journals = journals;
        this.mapping = mapping;
        this.services = services;
        this.tx = tx;
    }

    @Transactional
    public Aggregated aggregate(LedgerPosting p) {
        Objects.requireNonNull(p.journalEntryId(), "journalEntryId");
        store.advisoryLock("gl_aggregate:" + p.journalEntryId());
        var existing = store.row("select row_to_json(t)::text from gl_journal t where t.source_journal_entry_id = ?",
                p.journalEntryId());
        if (existing.isPresent()) {
            return new Aggregated(existing.get().toString(), true);
        }
        record Key(UUID glAccountId, JournalLine.Direction direction, String currency, UUID productId) {}
        Map<Key, BigDecimal[]> sums = new LinkedHashMap<>();
        for (PostingLine l : p.lines()) {
            UUID gl = l.glAccountId() != null ? l.glAccountId() : mapping.resolve(new MappingRule.Criteria(
                            p.legalEntityId(), l.productId(), l.ledgerAccountType(), l.ownerType(), p.entryType(),
                            "POSITIVE", p.valueDate()))
                    .map(r -> r.rule().glAccountId())
                    .orElseThrow(() -> ApiException.unprocessable("no_mapping_rule", "No GL mapping rule for ledger account "
                            + l.ledgerAccountId() + " (" + l.ledgerAccountType() + ", " + p.entryType() + ")"));
            BigDecimal[] s = sums.computeIfAbsent(new Key(gl, JournalLine.Direction.valueOf(l.direction()), l.currency(),
                    l.productId()), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            s[0] = s[0].add(l.amount());
            s[1] = s[1].add(l.baseAmount() == null ? l.amount() : l.baseAmount());
        }
        List<JournalLine> lines = new ArrayList<>();
        sums.forEach((k, s) -> lines.add(new JournalLine(k.glAccountId(), k.direction(), s[0], k.currency(), s[1],
                null, null, k.productId())));
        String description = p.description() != null ? p.description() : "Ledger entry " + p.journalEntryId();
        return new Aggregated(journals.postSystemJournal(p.legalEntityId(), JournalService.AUTO_AGGREGATE,
                p.valueDate(), description, p.journalEntryId(), null, lines), false);
    }

    /**
     * Aggregates a {@code ledger.journal_entry.posted} event payload (the JournalEntry API representation),
     * enriching it with the ledger's legal entity and the accounts' type / owner / GL control account from
     * ledger-service. Used by an event consumer; the ledger lookups happen before the GL transaction starts.
     */
    public Aggregated aggregateLedgerEvent(JsonNode entry) {
        UUID ledgerId = UUID.fromString(entry.get("ledger_id").asString());
        JsonNode ledger = services.get("ledger-service", "/v1/ledgers/" + ledgerId);
        UUID le = UUID.fromString(ledger.get("legal_entity_id").asString());
        Map<String, JsonNode> accounts = new HashMap<>();
        List<PostingLine> lines = new ArrayList<>();
        for (JsonNode e : entry.path("entries")) {
            String accountId = e.get("ledger_account_id").asString();
            JsonNode a = accounts.computeIfAbsent(accountId,
                    id -> services.get("ledger-service", "/v1/ledger-accounts/" + id));
            BigDecimal amount = new BigDecimal(e.get("amount").asString());
            lines.add(new PostingLine(UUID.fromString(accountId), Fields.text(a, "account_type"),
                    Fields.uuid(a, "product_id"), Fields.text(a, "owner_type"), Fields.uuid(a, "gl_account_id"),
                    e.get("direction").asString(), amount, e.get("currency").asString().trim(),
                    e.hasNonNull("base_amount") ? new BigDecimal(e.get("base_amount").asString()) : amount));
        }
        LocalDate valueDate = entry.hasNonNull("value_date") ? LocalDate.parse(entry.get("value_date").asString())
                : OffsetDateTime.parse(entry.get("effective_at").asString()).toLocalDate();
        LedgerPosting posting = new LedgerPosting(UUID.fromString(entry.get("id").asString()), le,
                Fields.text(entry, "entry_type"), valueDate, Fields.text(entry, "description"), lines);
        return tx.execute(status -> aggregate(posting));
    }
}
