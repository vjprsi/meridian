package io.meridian.rewards.application;

import static io.meridian.rewards.application.Json.text;

import io.meridian.common.error.ApiException;
import io.meridian.rewards.api.generated.model.CashbackTransaction;
import io.meridian.rewards.domain.Lifecycles;
import io.meridian.rewards.infrastructure.ExternalServices;
import io.meridian.rewards.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cashback awards: PENDING during the dispute window, CONFIRMED, then PAID through ledger-service (debit the
 * program's funding account, credit the customer's deposit account). A failed payout stays CONFIRMED with the
 * ledger's reason and is retried by the next payout; the ledger key is derived from the award id, so a retry can
 * never pay twice. Reversing a PAID award reverses its journal entry.
 */
@Service
public class CashbackService {
    static final String T = "cashback_transaction";

    private final Rows rows;
    private final Events events;
    private final ExternalServices external;

    public CashbackService(Rows rows, Events events, ExternalServices external) {
        this.rows = rows;
        this.events = events;
        this.external = external;
    }

    @Transactional
    public ObjectNode confirm(UUID id) {
        ObjectNode c = rows.lock(T, id);
        return confirmLocked(c);
    }

    ObjectNode confirmLocked(ObjectNode c) {
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.CASHBACK.apply("confirm", text(c, "status")));
        ObjectNode row = rows.update(T, Json.uuid(c, "id"), v);
        events.publish("engagement.cashback.confirmed", T, row, CashbackTransaction.class);
        return row;
    }

    /** Pays a confirmed award now; the answer is PAID, or CONFIRMED with failure_reason when the ledger refused. */
    @Transactional
    public ObjectNode pay(UUID id) {
        ObjectNode c = rows.lock(T, id);
        Lifecycles.CASHBACK.apply("pay", text(c, "status"));
        return payLocked(c);
    }

    ObjectNode payLocked(ObjectNode c) {
        UUID id = Json.uuid(c, "id");
        ObjectNode program = rows.get("rewards_program", Json.uuid(c, "rewards_program_id"));
        ObjectNode v = rows.object();
        v.put("payout_attempts", c.path("payout_attempts").asInt() + 1);
        try {
            UUID credit = Json.uuid(c, "credit_account_id");
            if (credit == null) {
                throw ApiException.unprocessable("no_credit_account", "No account to credit: set credit_account_id or the member's default");
            }
            ExternalServices.CustomerAccount acct = external.customerAccount(credit);
            if (acct.currency() != null && !acct.currency().equals(text(c, "currency"))) {
                throw ApiException.unprocessable("currency_mismatch", "The credit account is in " + acct.currency());
            }
            UUID journal = external.payout(Json.uuid(program, "ledger_id"), Json.uuid(program, "funding_ledger_account_id"),
                    acct.ledgerAccountId(), new BigDecimal(text(c, "cashback_amount")), text(c, "currency"), "CASHBACK", id,
                    Json.truncate("Cashback " + text(program, "code"), 500), "rewards-cashback:" + id);
            v.put("status", "PAID");
            v.put("journal_entry_id", journal.toString());
            v.put("paid_at", rows.now().toString());
            v.putNull("failure_reason");
            ObjectNode row = rows.update(T, id, v);
            events.publish("engagement.cashback.paid", T, row, CashbackTransaction.class);
            return row;
        } catch (ApiException e) {
            v.put("failure_reason", Json.truncate(e.code() + ": " + e.getMessage(), 500));
            ObjectNode row = rows.update(T, id, v);
            events.publish("engagement.cashback.payout_failed", T, row, CashbackTransaction.class);
            return row;
        }
    }

    @Transactional
    public ObjectNode reverse(UUID id, String reason) {
        return reverseLocked(rows.lock(T, id), reason);
    }

    ObjectNode reverseLocked(ObjectNode c, String reason) {
        UUID id = Json.uuid(c, "id");
        String from = text(c, "status");
        ObjectNode v = rows.object();
        v.put("status", Lifecycles.CASHBACK.apply("reverse", from));
        v.put("reversal_reason", Json.truncate(reason, 255));
        if ("PAID".equals(from) && text(c, "journal_entry_id") != null) {
            UUID rev = external.reverse(Json.uuid(c, "journal_entry_id"), Json.truncate("Cashback reversed: " + reason, 255),
                    "rewards-cashback-reversal:" + id);
            v.put("reversal_journal_entry_id", rev.toString());
        }
        ObjectNode row = rows.update(T, id, v);
        events.publish("engagement.cashback.reversed", T, row, CashbackTransaction.class);
        return row;
    }
}
