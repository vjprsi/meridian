package io.meridian.reconciliation;

import io.meridian.common.refs.CounterpartyAccounts;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stateful stand-in for ledger-service as reconciliation-service sees it: it accepts the request bodies
 * payment-service / card-clearing send (POST /v1/journal-entries and POST /v1/holds/{id}/capture with
 * {@code reference} and {@code external_refs}) and stores the ledger entries the way ledger-service does
 * (PostingService): the journal's reference, entry type, value date and payment references copied to every line, the
 * counterparty account replaced by its tenant-keyed pseudonym + last 4 ({@link CounterpartyAccounts}, same secret as
 * the service under test), reversals keeping the references of the entry they reverse. It serves
 * {@code GET /v1/ledger-entries?ledger_account_id=...&limit=...&cursor=...} pages like the generated list endpoint.
 * The real ledger behaviour is asserted in ledger-service's LedgerPostingIT (paymentRefsAreStoredCopied...).
 */
final class PaymentLedgerFake {
    private static final Set<String> REF_KEYS = Set.of("uetr", "end_to_end_id", "instruction_id", "bank_reference",
            "scheme_reference", "batch_id", "counterparty_name", "purpose_code");

    private final JsonMapper json;
    private final UUID tenant;
    private final CounterpartyAccounts accounts = new CounterpartyAccounts(CounterpartyAccounts.DEV_KEY);
    private final List<ObjectNode> lines = new ArrayList<>();
    private final Map<UUID, ObjectNode> journals = new HashMap<>();

    PaymentLedgerFake(JsonMapper json, UUID tenant) {
        this.json = json;
        this.tenant = tenant;
    }

    /** POST /v1/journal-entries (body as payment-service's LedgerClient.post sends it). */
    synchronized ObjectNode post(JsonNode body, LocalDate valueDate) {
        return book(body, body.path("entries"), valueDate, body.path("entry_type").asString("STANDARD"), null);
    }

    /**
     * POST /v1/holds/{id}/capture (body as LedgerClient.capture sends it): debit the held account, credit the counter
     * account, both lines carrying the capture's reference and payment references.
     */
    synchronized ObjectNode capture(UUID heldAccount, String currency, JsonNode body, LocalDate valueDate) {
        ArrayNode legs = json.createArrayNode();
        legs.addObject().put("ledger_account_id", heldAccount.toString()).put("direction", "DEBIT")
                .put("amount", body.get("amount").asString()).put("currency", currency);
        legs.addObject().put("ledger_account_id", body.get("counter_account_id").asString()).put("direction", "CREDIT")
                .put("amount", body.get("amount").asString()).put("currency", currency);
        return book(body, legs, valueDate, "STANDARD", null);
    }

    /** POST /v1/journal-entries/{id}/reverse: mirrored lines, same reference and payment references. */
    synchronized ObjectNode reverse(UUID journalId, LocalDate valueDate) {
        ObjectNode original = journals.get(journalId);
        ArrayNode legs = json.createArrayNode();
        for (JsonNode l : original.get("entries")) {
            legs.addObject().put("ledger_account_id", l.get("ledger_account_id").asString())
                    .put("direction", "DEBIT".equals(l.get("direction").asString()) ? "CREDIT" : "DEBIT")
                    .put("amount", l.get("amount").asString()).put("currency", l.get("currency").asString());
        }
        return book(original, legs, valueDate, "REVERSAL", journalId);
    }

    private ObjectNode book(JsonNode req, JsonNode legs, LocalDate valueDate, String entryType, UUID reversalOf) {
        UUID journalId = UUID.randomUUID();
        ObjectNode refs = json.createObjectNode();
        JsonNode in = reversalOf == null ? req.path("external_refs") : req; // a reversal copies the stored columns
        for (String k : REF_KEYS) {
            if (in.hasNonNull(k)) {
                refs.put(k, k.equals("uetr") ? in.get(k).asString().toLowerCase() : in.get(k).asString());
            }
        }
        if (reversalOf == null && in.hasNonNull("counterparty_account")) {
            String account = in.get("counterparty_account").asString();
            refs.put("counterparty_account_hash", accounts.pseudonym(tenant, account));
            refs.put("counterparty_account_last4", CounterpartyAccounts.last4(account));
        } else if (reversalOf != null) {
            for (String k : List.of("counterparty_account_hash", "counterparty_account_last4")) {
                if (in.hasNonNull(k)) {
                    refs.put(k, in.get(k).asString());
                }
            }
        }
        ObjectNode journal = refs.deepCopy();
        journal.put("id", journalId.toString());
        journal.put("entry_type", entryType);
        if (req.hasNonNull("reference")) {
            journal.put("reference", req.get("reference").asString());
        }
        ArrayNode out = journal.putArray("entries");
        int lineNo = 0;
        for (JsonNode leg : legs) {
            ObjectNode row = refs.deepCopy();
            row.put("id", UUID.randomUUID().toString());
            row.put("journal_entry_id", journalId.toString());
            row.put("ledger_account_id", leg.get("ledger_account_id").asString());
            row.put("line_no", ++lineNo);
            row.put("direction", leg.get("direction").asString());
            row.put("amount", new BigDecimal(leg.get("amount").asString()).setScale(8).toPlainString());
            row.put("currency", leg.get("currency").asString());
            row.put("value_date", valueDate.toString());
            row.put("effective_at", valueDate + "T10:00:00Z");
            row.put("entry_type", entryType);
            if (journal.hasNonNull("reference")) {
                row.put("reference", journal.get("reference").asString());
            }
            if (req.hasNonNull("description")) {
                row.put("description", req.get("description").asString());
            }
            lines.add(row);
            out.add(row);
        }
        journals.put(journalId, journal);
        return journal;
    }

    /** Id of the line of {@code journal} on {@code account}. */
    static UUID lineOn(JsonNode journal, UUID account) {
        for (JsonNode l : journal.get("entries")) {
            if (l.get("ledger_account_id").asString().equals(account.toString())) {
                return UUID.fromString(l.get("id").asString());
            }
        }
        throw new AssertionError("journal has no line on " + account);
    }

    /** GET /v1/ledger-entries?ledger_account_id=...&limit=...[&cursor=...] (newest first, like the generated list). */
    synchronized JsonNode get(String path) {
        Map<String, String> q = new HashMap<>();
        for (String p : path.substring(path.indexOf('?') + 1).split("&")) {
            int eq = p.indexOf('=');
            q.put(p.substring(0, eq), URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8));
        }
        String account = q.get("ledger_account_id");
        int limit = Integer.parseInt(q.getOrDefault("limit", "50"));
        int from = q.containsKey("cursor") ? Integer.parseInt(q.get("cursor")) : 0;
        List<ObjectNode> mine = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).get("ledger_account_id").asString().equals(account)) {
                mine.add(lines.get(i));
            }
        }
        ObjectNode page = json.createObjectNode();
        ArrayNode data = page.putArray("data");
        int to = Math.min(mine.size(), from + limit);
        for (int i = from; i < to; i++) {
            data.add(mine.get(i));
        }
        page.put("has_more", to < mine.size());
        if (to < mine.size()) {
            page.put("next_cursor", String.valueOf(to));
        }
        return page;
    }
}
