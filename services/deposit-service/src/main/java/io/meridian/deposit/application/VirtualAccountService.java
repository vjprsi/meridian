package io.meridian.deposit.application;

import static io.meridian.deposit.infrastructure.Rows.dec;
import static io.meridian.deposit.infrastructure.Rows.str;
import static io.meridian.deposit.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.deposit.domain.AccountNumbers;
import io.meridian.deposit.domain.NumberingRule;
import io.meridian.deposit.domain.Lifecycle;
import io.meridian.deposit.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Virtual accounts: numbers (and virtual IBANs) that route incoming funds to a master account, each backed by a
 * ledger sub-account under the master's ledger account.
 */
@Service
public class VirtualAccountService {
    static final String TABLE = "virtual_account";
    static final int MAX_BULK = 10_000;
    private static final Set<String> AUTO = Set.of("", "AUTO", "GENERATE");

    private final Rows rows;
    private final AccountSupport accounts;
    private final LedgerPort ledger;
    private final Events events;
    private final AccountNumbering numbering;

    public VirtualAccountService(Rows rows, AccountSupport accounts, LedgerPort ledger, Events events,
                                 AccountNumbering numbering) {
        this.rows = rows;
        this.accounts = accounts;
        this.ledger = ledger;
        this.events = events;
        this.numbering = numbering;
    }

    @Transactional
    public ObjectNode create(Object body) {
        ObjectNode v = rows.columns(TABLE, body);
        ObjectNode master = accounts.reference(uuid(v, "master_account_id"), "master_account_id");
        accounts.lock(uuid(master, "id"));
        return createOne(v, master);
    }

    private ObjectNode createOne(ObjectNode v, ObjectNode master) {
        if (!"ACTIVE".equals(str(master, "status"))) {
            throw ApiException.conflict("account_not_active", "Virtual accounts need an ACTIVE master account");
        }
        accounts.requireCurrency(master, str(v, "currency"));
        BigDecimal expected = dec(v, "expected_amount");
        if (expected != null && expected.signum() <= 0) {
            throw ApiException.unprocessable("invalid_expected_amount", "expected_amount must be positive");
        }
        // Same numbering as the master account's legal entity (virtual IBANs in IBAN countries).
        NumberingRule rule = numbering.rule(uuid(master, "legal_entity_id"), uuid(master, "branch_id"));
        NumberingRule.Numbers numbers = numbering.allocate(rule);
        String number = numbers.accountNumber();
        v.put("virtual_account_number", number);
        String iban = str(v, "iban");
        if (iban != null && AUTO.contains(iban.trim().toUpperCase(Locale.ROOT))) {
            if (numbers.iban() != null) {
                v.put("iban", numbers.iban());
            } else {
                v.remove("iban");
            }
        } else if (iban != null) {
            String normalised = iban.replace(" ", "").toUpperCase(Locale.ROOT);
            if (!AccountNumbers.isValidIban(normalised, normalised.startsWith(rule.country()) ? rule.ibanLength() : null)) {
                throw ApiException.unprocessable("invalid_iban", "iban fails the ISO 13616 structure or mod-97 check");
            }
            v.put("iban", normalised);
        }
        UUID id = Ids.newId();
        v.put("id", id.toString());
        v.put("status", "ACTIVE");
        v.put("ledger_account_id", ledger.openAccount(LedgerPort.key("va-ledger-account", id, null), "VA-" + number,
                "Virtual account " + number, str(v, "currency"), BigDecimal.ZERO, accounts.requireLedger(master),
                "VIRTUAL_ACCOUNT", id).toString());
        ObjectNode row = rows.insert(TABLE, v);
        // the payload names the master account's holder, so webhook subscriptions filtered on customer_id receive it
        events.publish("accounts.virtual_account.created", TABLE, row.deepCopy().put("customer_id", str(master, "customer_id")));
        return row;
    }

    /** Bulk creation, all-or-nothing: {@code items} is an array of VirtualAccountCreate objects. */
    @Transactional
    public ObjectNode bulk(JsonNode items) {
        List<JsonNode> list = new ArrayList<>();
        if (items == null || !items.isArray()) {
            throw ApiException.unprocessable("invalid_items", "items must be an array of virtual accounts");
        }
        items.forEach(list::add);
        if (list.isEmpty()) {
            throw ApiException.unprocessable("no_items", "items contains no virtual accounts");
        }
        if (list.size() > MAX_BULK) {
            throw ApiException.unprocessable("too_many_items", "At most " + MAX_BULK + " virtual accounts per request");
        }
        java.util.Map<UUID, ObjectNode> masters = new java.util.HashMap<>();
        ObjectNode out = rows.object();
        ArrayNode created = out.putArray("data");
        int index = 0;
        for (JsonNode item : list) {
            if (!item.isObject()) {
                throw ApiException.unprocessable("invalid_item", "items[" + index + "] must be an object");
            }
            ObjectNode v = rows.object();
            for (String f : List.of("master_account_id", "iban", "purpose", "owner_customer_id",
                    "owner_reference", "currency", "expected_amount", "single_use", "expires_at", "metadata")) {
                if (item.hasNonNull(f)) {
                    v.set(f, item.get(f));
                }
            }
            for (String required : List.of("master_account_id", "purpose", "currency")) {
                if (!v.has(required)) {
                    throw ApiException.unprocessable("field_required", "items[" + index + "]." + required + " is required");
                }
            }
            UUID masterId = uuid(v, "master_account_id");
            ObjectNode master = masters.computeIfAbsent(masterId, m -> {
                accounts.reference(m, "items[].master_account_id");
                return accounts.lock(m);
            });
            created.add(createOne(v, master));
            index++;
        }
        out.put("created", created.size());
        return out;
    }

    @Transactional
    public ObjectNode close(UUID id, String reason) {
        ObjectNode va = rows.lock(TABLE, id);
        Lifecycle.VIRTUAL_ACCOUNT.require(str(va, "status"), "CLOSED");
        UUID vaLedger = uuid(va, "ledger_account_id");
        if (vaLedger != null) {
            BigDecimal balance = LedgerPort.posted(ledger.balance(vaLedger));
            if (balance.signum() > 0) {
                ObjectNode master = accounts.get(uuid(va, "master_account_id"));
                ledger.post(new LedgerPort.Posting(LedgerPort.key("va-close-sweep", id, null), "TRANSFER",
                        "Virtual account " + str(va, "virtual_account_number") + " closed", str(va, "currency"),
                        "VIRTUAL_ACCOUNT", id, null, List.of(
                                LedgerPort.Leg.debit(vaLedger, balance, "Virtual account closed"),
                                LedgerPort.Leg.credit(accounts.requireLedger(master), balance, "Virtual account closed"))));
            } else if (balance.signum() < 0) {
                throw ApiException.unprocessable("negative_balance", "Virtual account has a negative balance");
            }
            ledger.restrict(vaLedger, "ACTIVE", "CLOSED", reason, LedgerPort.key("va-close", id, null));
        }
        ObjectNode v = rows.object();
        v.put("status", "CLOSED");
        return rows.update(TABLE, id, v);
    }

    public JsonNode balance(UUID id) {
        return ledger.balance(requireLedger(rows.get(TABLE, id)));
    }

    public JsonNode transactions(UUID id, Integer limit, String cursor) {
        return ledger.entries(requireLedger(rows.get(TABLE, id)), null, null, limit, cursor);
    }

    private static UUID requireLedger(JsonNode va) {
        UUID id = uuid(va, "ledger_account_id");
        if (id == null) {
            throw ApiException.conflict("no_ledger_account", "Virtual account has no ledger sub-account");
        }
        return id;
    }
}
