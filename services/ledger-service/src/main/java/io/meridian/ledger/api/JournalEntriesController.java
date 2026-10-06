package io.meridian.ledger.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.ledger.api.generated.JournalEntriesApi;
import io.meridian.ledger.api.generated.model.JournalEntriesReverseRequest;
import io.meridian.ledger.api.generated.model.JournalEntry;
import io.meridian.ledger.api.generated.model.JournalEntryCreate;
import io.meridian.ledger.api.generated.model.LedgerEntryPage;
import io.meridian.ledger.application.PostingService;
import io.meridian.ledger.domain.PostingLine;
import io.meridian.ledger.infrastructure.LedgerStore;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Journal entries: posting (double-entry engine), retrieval with lines, reversal. */
@RestController
public class JournalEntriesController implements JournalEntriesApi {
    private static final Set<String> CUSTOM = Set.of("custom_field_1", "custom_field_2", "custom_field_3",
            "custom_field_4", "custom_field_5");

    private final PostingService posting;
    private final LedgerStore store;
    private final CrudEngine crud;
    private final JsonMapper json;

    public JournalEntriesController(PostingService posting, LedgerStore store, CrudEngine crud, JsonMapper json) {
        this.posting = posting;
        this.store = store;
        this.crud = crud;
        this.json = json;
    }

    @Override
    public ResponseEntity<JournalEntry> journalEntriesCreate(String idempotencyKey, JournalEntryCreate body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        List<PostingLine> lines = new ArrayList<>();
        for (JsonNode e : req.path("entries")) {
            lines.add(new PostingLine(
                    UUID.fromString(e.get("ledger_account_id").asString()),
                    PostingLine.Direction.valueOf(e.get("direction").asString()),
                    new BigDecimal(e.get("amount").asString()),
                    e.get("currency").asString(),
                    e.hasNonNull("fx_rate") ? new BigDecimal(e.get("fx_rate").asString()) : null,
                    e.hasNonNull("description") ? e.get("description").asString() : null));
        }
        ObjectNode custom = json.createObjectNode();
        CUSTOM.forEach(c -> {
            if (req.hasNonNull(c)) {
                custom.set(c, req.get(c));
            }
        });
        PostingService.Posted result = posting.post(new PostingService.Posting(
                UUID.fromString(req.get("ledger_id").asString()), idempotencyKey,
                text(req, "entry_type"), text(req, "description"),
                req.hasNonNull("effective_at") ? OffsetDateTime.parse(req.get("effective_at").asString()) : null,
                req.hasNonNull("value_date") ? LocalDate.parse(req.get("value_date").asString()) : null,
                TenantContext.principal(), text(req, "source_type"),
                req.hasNonNull("source_id") ? UUID.fromString(req.get("source_id").asString()) : null,
                text(req, "correlation_id"), null, req.get("metadata"), custom, lines));
        return respond(result);
    }

    @Override
    public ResponseEntity<JournalEntry> journalEntriesGet(UUID id) {
        TenantContext.require();
        String row = store.journalEntryJson(id).orElseThrow(() -> ApiException.notFound("journal entry", id));
        return ResponseEntity.ok(crud.read(row, JournalEntry.class));
    }

    @Override
    public ResponseEntity<JournalEntry> journalEntriesReverse(UUID id, String idempotencyKey,
                                                              JournalEntriesReverseRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        PostingService.Posted result = posting.reverse(id, idempotencyKey, text(req, "reason"),
                req.hasNonNull("effective_at") ? OffsetDateTime.parse(req.get("effective_at").asString()) : null);
        return respond(result);
    }

    @Override
    public ResponseEntity<LedgerEntryPage> journalEntriesEntries(UUID id, Integer limit, String cursor) {
        return crud.list("ledger_entry", LedgerEntryPage.class,
                Crud.params().with("journal_entry_id", id).with("limit", limit).with("cursor", cursor));
    }

    private ResponseEntity<JournalEntry> respond(PostingService.Posted result) {
        JournalEntry entry = crud.read(result.json(), JournalEntry.class);
        return result.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replayed", "true").body(entry)
                : ResponseEntity.status(HttpStatus.CREATED).body(entry);
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asString() : null;
    }
}
