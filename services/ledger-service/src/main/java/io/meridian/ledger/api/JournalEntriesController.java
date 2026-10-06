package io.meridian.ledger.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.ledger.api.generated.JournalEntriesApi;
import io.meridian.ledger.api.generated.model.JournalEntriesBatchRequest;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Journal entries: posting (double-entry engine), batch posting, retrieval with lines, reversal. */
@RestController
public class JournalEntriesController implements JournalEntriesApi {
    private static final Set<String> CUSTOM = Set.of("custom_field_1", "custom_field_2", "custom_field_3",
            "custom_field_4", "custom_field_5");
    private static final int MAX_BATCH = 1000;

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
        return respond(posting.post(toPosting(json.valueToTree(body), idempotencyKey)));
    }

    /**
     * Posts each item as its own atomic journal entry (item i under key {@code <Idempotency-Key>:<i>}, so a replayed
     * batch replays every item). One failing item does not affect the others; the result lists every outcome.
     */
    @Override
    public ResponseEntity<Map<String, Object>> journalEntriesBatch(String idempotencyKey, JournalEntriesBatchRequest body) {
        TenantContext.require();
        List<Object> items = body.getEntries() == null ? List.of() : body.getEntries();
        if (items.isEmpty()) {
            throw ApiException.unprocessable("empty_batch", "entries must contain at least one journal entry");
        }
        if (items.size() > MAX_BATCH) {
            throw ApiException.unprocessable("batch_too_large", "A batch holds at most " + MAX_BATCH + " journal entries");
        }
        String prefix = idempotencyKey.length() + 6 > 128 ? sha256(idempotencyKey) : idempotencyKey;
        ArrayNode results = json.createArrayNode();
        int posted = 0;
        int replayed = 0;
        int failed = 0;
        for (int i = 0; i < items.size(); i++) {
            ObjectNode r = results.addObject();
            r.put("index", i);
            try {
                JsonNode item = json.valueToTree(items.get(i));
                if (!item.isObject()) {
                    throw ApiException.badRequest("validation_failed", "Each batch item must be a journal entry object");
                }
                PostingService.Posted p = posting.post(toPosting(item, prefix + ":" + i));
                r.put("status", p.replayed() ? 200 : 201);
                r.set("journal_entry", json.readTree(p.json()));
                if (p.replayed()) {
                    replayed++;
                } else {
                    posted++;
                }
            } catch (ApiException e) {
                failed++;
                r.put("status", e.status());
                r.put("code", e.code());
                r.put("detail", e.getMessage());
            } catch (RuntimeException e) {
                failed++;
                r.put("status", 400);
                r.put("code", "validation_failed");
                r.put("detail", "Malformed journal entry: " + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("posted", posted);
        out.put("replayed", replayed);
        out.put("failed", failed);
        out.put("results", json.treeToValue(results, List.class));
        return ResponseEntity.ok(out);
    }

    private PostingService.Posting toPosting(JsonNode req, String idempotencyKey) {
        if (!req.hasNonNull("ledger_id") || !req.path("entries").isArray()) {
            throw ApiException.badRequest("validation_failed", "ledger_id and entries are required");
        }
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
        return new PostingService.Posting(
                UUID.fromString(req.get("ledger_id").asString()), idempotencyKey,
                text(req, "entry_type"), text(req, "description"),
                req.hasNonNull("effective_at") ? OffsetDateTime.parse(req.get("effective_at").asString()) : null,
                req.hasNonNull("value_date") ? LocalDate.parse(req.get("value_date").asString()) : null,
                TenantContext.principal(), text(req, "source_type"),
                req.hasNonNull("source_id") ? UUID.fromString(req.get("source_id").asString()) : null,
                text(req, "correlation_id"), text(req, "reference"), null, req.get("metadata"), custom, lines);
    }

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
