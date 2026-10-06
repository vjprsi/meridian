package io.meridian.loan;

import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-memory stand-in for ledger-service behind the mocked ServiceClient: realistic JSON responses, idempotent
 * journal entries by key, balances in normal-balance terms, insufficient-funds and freeze checks for customer
 * accounts, holds, value-dated entries. Every request is recorded so tests can assert what deposit-service sent.
 */
public class FakeLedger {
    public record Call(String method, String path, JsonNode body, String key) {}

    static final class Account {
        UUID id;
        String currency;
        String status = "ACTIVE";
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal overdraft = BigDecimal.ZERO;
        boolean allowNegative;
        int version;
        int rowVersion = 1;
        ObjectNode row;
        final List<ObjectNode> entries = new ArrayList<>();
    }

    private final JsonMapper json;
    private final Clock clock;
    private final Map<UUID, Account> accounts = new LinkedHashMap<>();
    private final Map<String, ObjectNode> byKey = new HashMap<>();
    private final Map<UUID, ObjectNode> journals = new HashMap<>();
    private final Map<UUID, ObjectNode> holds = new HashMap<>();
    private final Map<UUID, UUID> reversals = new HashMap<>();
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    public FakeLedger(JsonMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ ServiceClient surface

    public synchronized JsonNode post(String service, String path, Object body, String key) {
        JsonNode b = body == null ? json.createObjectNode() : json.valueToTree(body);
        calls.add(new Call("POST", path, b, key));
        if (path.equals("/v1/ledger-accounts")) {
            return createAccount(b, key);
        }
        if (path.equals("/v1/journal-entries")) {
            return postEntry(b, key, null);
        }
        if (path.equals("/v1/holds")) {
            return placeHold(b, key);
        }
        String[] p = path.split("/");
        if (path.startsWith("/v1/journal-entries/") && path.endsWith("/reverse")) {
            return reverse(UUID.fromString(p[3]), key);
        }
        if (path.startsWith("/v1/holds/") && path.endsWith("/release")) {
            ObjectNode h = holds.get(UUID.fromString(p[3]));
            if (h == null) {
                throw ApiException.notFound("hold", p[3]);
            }
            if ("ACTIVE".equals(h.get("status").asString())) {
                Account a = account(UUID.fromString(h.get("ledger_account_id").asString()));
                a.pending = a.pending.subtract(new BigDecimal(h.get("amount").asString()));
                h.put("status", "RELEASED");
            }
            return h;
        }
        if (path.startsWith("/v1/ledger-accounts/")) {
            Account a = account(UUID.fromString(p[3]));
            switch (p[4]) {
                case "freeze" -> a.status = b.get("status").asString();
                case "unfreeze" -> a.status = "ACTIVE"; // idempotent like ledger-service: ACTIVE stays ACTIVE (200)
                case "overdraft-limit" -> {
                    a.overdraft = new BigDecimal(b.get("limit").asString());
                    a.row.put("overdraft_limit", a.overdraft.toPlainString());
                    a.rowVersion++;
                }
                case "close" -> {
                    if (a.balance.signum() != 0 || a.pending.signum() != 0) {
                        throw ApiException.unprocessable("balance_not_zero", "balance not zero");
                    }
                    a.status = "CLOSED";
                }
                default -> throw new IllegalArgumentException(path);
            }
            a.row.put("status", a.status);
            return a.row;
        }
        throw new IllegalArgumentException("unexpected POST " + service + " " + path);
    }

    public synchronized JsonNode get(String service, String path) {
        calls.add(new Call("GET", path, null, null));
        String[] pathAndQuery = path.split("\\?", 2);
        Map<String, String> q = new HashMap<>();
        if (pathAndQuery.length > 1) {
            for (String kv : pathAndQuery[1].split("&")) {
                String[] x = kv.split("=", 2);
                q.put(x[0], URLDecoder.decode(x[1], StandardCharsets.UTF_8));
            }
        }
        String[] p = pathAndQuery[0].split("/");
        Account a = account(UUID.fromString(p[3]));
        if (p.length == 4) {
            a.row.put("row_version", a.rowVersion);
            a.row.put("overdraft_limit", a.overdraft.toPlainString());
            return a.row;
        }
        return switch (p[4]) {
            case "balance" -> balance(a, a.balance);
            case "balance-at" -> {
                OffsetDateTime at = OffsetDateTime.parse(q.get("at"));
                BigDecimal bal = BigDecimal.ZERO;
                for (ObjectNode e : a.entries) {
                    if (!OffsetDateTime.parse(e.get("created_at").asString()).isAfter(at)) {
                        bal = new BigDecimal(e.get("balance_after").asString());
                    }
                }
                yield balance(a, bal);
            }
            case "entries" -> {
                OffsetDateTime from = q.containsKey("from") ? OffsetDateTime.parse(q.get("from")) : null;
                OffsetDateTime to = q.containsKey("to") ? OffsetDateTime.parse(q.get("to")) : null;
                ObjectNode page = json.createObjectNode();
                ArrayNode data = page.putArray("data");
                List<ObjectNode> list = new ArrayList<>(a.entries);
                Collections.reverse(list);
                for (ObjectNode e : list) {
                    OffsetDateTime c = OffsetDateTime.parse(e.get("created_at").asString());
                    if ((from == null || !c.isBefore(from)) && (to == null || c.isBefore(to))) {
                        data.add(e);
                    }
                }
                page.put("has_more", false);
                yield page;
            }
            default -> throw new IllegalArgumentException("unexpected GET " + path);
        };
    }

    // ------------------------------------------------------------------ test helpers

    /** Opens a customer (credit-normal) account directly in the fake, e.g. a deposit account's ledger account. */
    public synchronized UUID openCustomerAccount(String currency) {
        ObjectNode b = json.createObjectNode();
        b.put("currency", currency);
        b.put("account_type", "LIABILITY");
        b.put("normal_balance", "CREDIT");
        return UUID.fromString(createAccount(b, null).get("id").asString());
    }

    /** Raw balance (credits minus debits) of any account the fake knows. */
    public synchronized BigDecimal rawBalance(UUID account) {
        return account(account).balance;
    }

    /** An external credit (e.g. incoming payment) to a customer account, optionally value-dated. */
    public synchronized UUID credit(UUID account, String amount, OffsetDateTime effectiveAt) {
        ObjectNode body = json.createObjectNode();
        body.put("entry_type", "PAYMENT");
        body.put("description", "Incoming payment");
        if (effectiveAt != null) {
            body.put("value_date", effectiveAt.toLocalDate().toString());
        }
        ArrayNode e = body.putArray("entries");
        e.addObject().put("ledger_account_id", UUID.randomUUID().toString()).put("direction", "DEBIT")
                .put("amount", amount).put("currency", account(account).currency);
        e.addObject().put("ledger_account_id", account.toString()).put("direction", "CREDIT")
                .put("amount", amount).put("currency", account(account).currency);
        return UUID.fromString(postEntry(body, "test-credit-" + UUID.randomUUID(), effectiveAt).get("id").asString());
    }

    public synchronized BigDecimal balanceOf(UUID account) {
        return account(account).balance;
    }

    public synchronized BigDecimal pendingOf(UUID account) {
        return account(account).pending;
    }

    public synchronized String statusOf(UUID account) {
        return account(account).status;
    }

    public synchronized JsonNode accountRow(UUID account) {
        return account(account).row;
    }

    public List<Call> calls(String method, String pathPrefix) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.method().equals(method) && c.path().startsWith(pathPrefix)).toList();
        }
    }

    public Call lastPosting() {
        List<Call> posts = calls("POST", "/v1/journal-entries");
        return posts.getLast();
    }

    // ------------------------------------------------------------------ internals

    private Account account(UUID id) {
        Account a = accounts.get(id);
        if (a == null) {
            throw ApiException.notFound("ledger account", id);
        }
        return a;
    }

    private ObjectNode createAccount(JsonNode b, String key) {
        if (key != null && byKey.containsKey(key)) {
            return byKey.get(key);
        }
        Account a = new Account();
        a.id = UUID.randomUUID();
        a.currency = b.get("currency").asString();
        a.overdraft = new BigDecimal(b.path("overdraft_limit").asString("0"));
        a.allowNegative = b.path("allow_negative").asBoolean(false);
        ObjectNode row = ((ObjectNode) b).deepCopy();
        row.put("id", a.id.toString());
        row.put("status", "ACTIVE");
        row.put("row_version", 1);
        a.row = row;
        accounts.put(a.id, a);
        if (key != null) {
            byKey.put(key, row);
        }
        return row;
    }

    private ObjectNode balance(Account a, BigDecimal posted) {
        ObjectNode n = json.createObjectNode();
        n.put("id", UUID.nameUUIDFromBytes(a.id.toString().getBytes(StandardCharsets.UTF_8)).toString());
        n.put("ledger_account_id", a.id.toString());
        n.put("currency", a.currency);
        n.put("posted_balance", posted.toPlainString());
        n.put("posted_debits", "0");
        n.put("posted_credits", "0");
        n.put("pending_debits", a.pending.toPlainString());
        n.put("pending_credits", "0");
        n.put("available_balance", posted.subtract(a.pending).add(a.overdraft).toPlainString());
        n.put("account_version", a.version);
        n.put("created_at", now().toString());
        n.put("updated_at", now().toString());
        n.put("row_version", 1);
        return n;
    }

    private ObjectNode placeHold(JsonNode b, String key) {
        if (byKey.containsKey(key)) {
            return byKey.get(key);
        }
        Account a = account(UUID.fromString(b.get("ledger_account_id").asString()));
        BigDecimal amount = new BigDecimal(b.get("amount").asString());
        if (a.balance.subtract(a.pending).add(a.overdraft).subtract(amount).signum() < 0) {
            throw ApiException.unprocessable("insufficient_funds", "insufficient available balance for the hold");
        }
        a.pending = a.pending.add(amount);
        ObjectNode h = ((ObjectNode) b).deepCopy();
        UUID id = UUID.randomUUID();
        h.put("id", id.toString());
        h.put("status", "ACTIVE");
        h.put("captured_amount", "0");
        holds.put(id, h);
        byKey.put(key, h);
        return h;
    }

    private ObjectNode reverse(UUID entryId, String key) {
        if (byKey.containsKey(key)) {
            return byKey.get(key);
        }
        if (reversals.containsKey(entryId)) {
            throw ApiException.conflict("already_reversed", "already reversed");
        }
        ObjectNode original = journals.get(entryId);
        ObjectNode body = json.createObjectNode();
        body.put("entry_type", "REVERSAL");
        body.put("description", "Reversal of " + entryId);
        ArrayNode lines = body.putArray("entries");
        for (JsonNode e : original.get("entries")) {
            lines.addObject().put("ledger_account_id", e.get("ledger_account_id").asString())
                    .put("direction", "DEBIT".equals(e.get("direction").asString()) ? "CREDIT" : "DEBIT")
                    .put("amount", e.get("amount").asString()).put("currency", e.get("currency").asString());
        }
        ObjectNode rev = postEntry(body, key, null);
        rev.put("reversal_of_id", entryId.toString());
        reversals.put(entryId, UUID.fromString(rev.get("id").asString()));
        return rev;
    }

    private ObjectNode postEntry(JsonNode b, String key, OffsetDateTime effective) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw ApiException.badRequest("idempotency_key_required", "bad key");
        }
        if (byKey.containsKey(key)) {
            return byKey.get(key);
        }
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (JsonNode e : b.get("entries")) {
            BigDecimal amt = new BigDecimal(e.get("amount").asString());
            if (amt.signum() <= 0) {
                throw ApiException.unprocessable("invalid_amount", "non-positive line");
            }
            if ("DEBIT".equals(e.get("direction").asString())) {
                debits = debits.add(amt);
            } else {
                credits = credits.add(amt);
            }
        }
        if (debits.compareTo(credits) != 0) {
            throw ApiException.unprocessable("unbalanced_entry", "debits " + debits + " != credits " + credits);
        }
        // validate first (customer accounts only; unknown ids are GL accounts with unlimited balance)
        Map<UUID, BigDecimal> delta = new HashMap<>();
        for (JsonNode e : b.get("entries")) {
            UUID id = UUID.fromString(e.get("ledger_account_id").asString());
            BigDecimal amt = new BigDecimal(e.get("amount").asString());
            boolean debit = "DEBIT".equals(e.get("direction").asString());
            Account a = accounts.get(id);
            if (a == null) {
                continue;
            }
            if ("CLOSED".equals(a.status) || "FROZEN".equals(a.status)
                    || debit && "FROZEN_DEBIT".equals(a.status) || !debit && "FROZEN_CREDIT".equals(a.status)) {
                throw ApiException.unprocessable("account_frozen", "Account " + id + " is " + a.status);
            }
            delta.merge(id, debit ? amt.negate() : amt, BigDecimal::add);
        }
        for (Map.Entry<UUID, BigDecimal> d : delta.entrySet()) {
            Account a = accounts.get(d.getKey());
            if (d.getValue().signum() < 0 && !a.allowNegative
                    && a.balance.add(d.getValue()).subtract(a.pending).add(a.overdraft).signum() < 0) {
                throw ApiException.unprocessable("insufficient_funds", "Account " + a.id + " has insufficient available balance");
            }
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime created = now();
        OffsetDateTime eff = effective != null ? effective : b.hasNonNull("value_date")
                ? java.time.LocalDate.parse(b.get("value_date").asString()).atStartOfDay().atOffset(ZoneOffset.UTC) : created;
        ObjectNode entry = ((ObjectNode) b).deepCopy();
        entry.put("id", id.toString());
        entry.put("idempotency_key", key);
        entry.put("effective_at", eff.toString());
        entry.put("value_date", eff.toLocalDate().toString());
        entry.put("created_at", created.toString());
        int lineNo = 0;
        ArrayNode lines = json.createArrayNode();
        for (JsonNode e : b.get("entries")) {
            UUID acc = UUID.fromString(e.get("ledger_account_id").asString());
            ObjectNode line = ((ObjectNode) e).deepCopy();
            line.put("id", UUID.randomUUID().toString());
            line.put("journal_entry_id", id.toString());
            line.put("line_no", ++lineNo);
            line.put("effective_at", eff.toString());
            line.put("created_at", created.toString());
            Account a = accounts.get(acc);
            if (a != null) {
                BigDecimal amt = new BigDecimal(e.get("amount").asString());
                a.balance = "DEBIT".equals(e.get("direction").asString()) ? a.balance.subtract(amt) : a.balance.add(amt);
                a.version++;
                line.put("account_version", a.version);
                line.put("balance_after", a.balance.toPlainString());
                line.put("base_amount", amt.toPlainString());
                a.entries.add(line);
            }
            lines.add(line);
        }
        entry.set("entries", lines);
        journals.put(id, entry);
        byKey.put(key, entry);
        return entry;
    }
}
