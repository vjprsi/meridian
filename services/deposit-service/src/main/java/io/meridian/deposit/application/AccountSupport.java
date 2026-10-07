package io.meridian.deposit.application;

import static io.meridian.deposit.infrastructure.Rows.str;
import static io.meridian.deposit.infrastructure.Rows.uuid;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.domain.AccountRestrictions;
import io.meridian.deposit.domain.Lifecycle;
import io.meridian.deposit.infrastructure.Rows;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shared deposit-account mechanics used by every use case: loading/locking, status transitions with history,
 * events and ledger restriction sync, and the guards (open, debit allowed, currency) that money movements need.
 */
@Component
public class AccountSupport {
    public static final String TABLE = "deposit_account";

    private final Rows rows;
    private final LedgerPort ledger;
    private final Events events;
    private final Clock clock;

    private final io.meridian.web.time.BusinessDates businessDates;

    public AccountSupport(Rows rows, LedgerPort ledger, Events events, Clock clock,
                          io.meridian.web.time.BusinessDates businessDates) {
        this.businessDates = businessDates;
        this.rows = rows;
        this.ledger = ledger;
        this.events = events;
        this.clock = clock;
    }

    public ObjectNode lock(UUID id) {
        return rows.lock(TABLE, id);
    }

    public ObjectNode get(UUID id) {
        return rows.get(TABLE, id);
    }

    /** Fails with 422 when a referenced account does not exist (as opposed to 404 for the addressed resource). */
    public ObjectNode reference(UUID id, String field) {
        if (id == null) {
            throw ApiException.unprocessable("field_required", field + " is required");
        }
        return rows.find(TABLE, id).orElseThrow(() -> ApiException.unprocessable("account_not_found",
                field + " " + id + " does not reference an existing deposit account"));
    }

    /** The tenant's business date (business-day-service), else this service's clock. */
    public LocalDate today() {
        return businessDates.today(() -> LocalDate.now(clock));
    }

    public OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    public UUID requireLedger(JsonNode account) {
        UUID id = uuid(account, "ledger_account_id");
        if (id == null) {
            throw ApiException.conflict("account_not_open",
                    "Deposit account " + str(account, "id") + " has no ledger account yet (approve it first)");
        }
        return id;
    }

    public void requireNotClosed(JsonNode account) {
        String s = str(account, "status");
        if ("CLOSED".equals(s) || "REJECTED".equals(s)) {
            throw ApiException.conflict("account_closed", "Deposit account " + str(account, "id") + " is " + s);
        }
    }

    /** Customer-initiated debits (pot deposits, standing orders, fees on demand) need an unrestricted account. */
    public void requireDebitAllowed(JsonNode account) {
        String s = str(account, "status");
        if (!AccountRestrictions.allowsDebit(s)) {
            throw ApiException.conflict("account_restricted",
                    "Deposit account " + str(account, "id") + " in status " + s + " does not allow debits");
        }
    }

    public void requireCreditAllowed(JsonNode account) {
        String s = str(account, "status");
        if (!AccountRestrictions.allowsCredit(s)) {
            throw ApiException.conflict("account_restricted",
                    "Deposit account " + str(account, "id") + " in status " + s + " does not accept credits");
        }
    }

    public void requireCurrency(JsonNode account, String currency) {
        if (currency != null && !currency.equals(str(account, "currency"))) {
            throw ApiException.unprocessable("currency_mismatch",
                    "Currency " + currency + " does not match the account currency " + str(account, "currency"));
        }
    }

    public List<String> activeBlockScopes(UUID accountId) {
        return rows.query("""
                select row_to_json(b)::text from account_block b
                where deposit_account_id = ? and status = 'ACTIVE' and scope <> 'AMOUNT'""", accountId)
                .stream().map(b -> str(b, "scope")).toList();
    }

    /**
     * Moves an account to {@code to}: validates the edge, records history, publishes {@code event} (default
     * status_changed) and aligns the ledger account's posting restriction.
     */
    public ObjectNode transition(ObjectNode account, String to, String reasonCode, String reason, ObjectNode extra,
                                 String event) {
        String from = str(account, "status");
        UUID id = uuid(account, "id");
        Lifecycle.DEPOSIT_ACCOUNT.require(from, to);
        ObjectNode values = extra == null ? rows.object() : extra;
        values.put("status", to);
        if (reason != null) {
            values.put("status_reason", reason.length() > 255 ? reason.substring(0, 255) : reason);
        } else {
            values.putNull("status_reason");
        }
        ObjectNode updated = rows.update(TABLE, id, values);
        ObjectNode history = history(id, from, to, reasonCode, reason);
        syncLedger(updated, from, to, history);
        events.publish(event == null ? "accounts.deposit_account.status_changed" : event, TABLE, updated);
        return updated;
    }

    /** Status change caused by a block placement/lift: recomputes the derived status. */
    public ObjectNode recomputeBlocks(ObjectNode account, String reasonCode, String reason) {
        String current = str(account, "status");
        String target = AccountRestrictions.statusFor(current, activeBlockScopes(uuid(account, "id")));
        if (target.equals(current)) {
            return account;
        }
        return transition(account, target, reasonCode, reason, null, null);
    }

    public ObjectNode history(UUID accountId, String from, String to, String reasonCode, String reason) {
        ObjectNode h = rows.object();
        h.put("deposit_account_id", accountId.toString());
        if (from != null) {
            h.put("from_status", from);
        }
        h.put("to_status", to);
        if (reasonCode != null) {
            h.put("reason_code", reasonCode);
        }
        if (reason != null) {
            h.put("reason", reason.length() > 500 ? reason.substring(0, 500) : reason);
        }
        return rows.insert("account_status_history", h);
    }

    private void syncLedger(JsonNode account, String from, String to, JsonNode history) {
        UUID ledgerAccount = uuid(account, "ledger_account_id");
        if (ledgerAccount == null || "CLOSED".equals(to)) {
            return; // closure closes the ledger account explicitly once the balance is zero
        }
        String before = AccountRestrictions.ledgerStatusFor(from);
        String after = AccountRestrictions.ledgerStatusFor(to);
        if (before.equals(after)) {
            return;
        }
        // freeze/unfreeze are idempotent in the ledger (already in the target status -> 200)
        ledger.restrict(ledgerAccount, before, after, str(history, "reason") == null ? to : str(history, "reason"),
                LedgerPort.key("restrict", uuid(history, "id"), null));
    }

    /** Four-eyes: the checker must differ from the maker. */
    public static void requireFourEyes(JsonNode row, String what) {
        String maker = str(row, "created_by");
        io.meridian.common.tenancy.FourEyes.require(maker,
                "The " + what + " must be approved by someone other than " + maker + " who created it");
    }
}
