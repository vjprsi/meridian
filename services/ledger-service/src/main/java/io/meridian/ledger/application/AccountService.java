package io.meridian.ledger.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.events.OutboxWriter;
import io.meridian.ledger.infrastructure.LedgerStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Ledger account lifecycle: open (with its balance row), freeze/unfreeze, close; ledger locking. */
@Service
public class AccountService {
    private static final Set<String> FREEZE_STATES = Set.of("FROZEN", "FROZEN_DEBIT", "FROZEN_CREDIT");

    private final LedgerStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AccountService(LedgerStore store, CrudEngine crud, OutboxWriter outbox, JdbcClient jdbc, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public String open(Object body) {
        ObjectNode values = crud.toColumns("ledger_account", body);
        UUID ledgerId = UUID.fromString(values.get("ledger_id").asString());
        LedgerStore.LedgerInfo ledger = store.ledger(ledgerId);
        if (!"ACTIVE".equals(ledger.status())) {
            throw ApiException.unprocessable("ledger_locked", "Ledger " + ledgerId + " is " + ledger.status());
        }
        if (values.has("parent_id")) {
            UUID parent = UUID.fromString(values.get("parent_id").asString());
            jdbc.sql("select 1 from ledger_account where id = ? and ledger_id = ?").params(parent, ledgerId)
                    .query(Integer.class).optional()
                    .orElseThrow(() -> ApiException.unprocessable("invalid_parent", "Parent account must be in the same ledger"));
        }
        UUID id = Ids.newId();
        values.put("id", id.toString());
        values.put("status", "ACTIVE");
        String row = crud.insert("ledger_account", values);
        ObjectNode balance = json.createObjectNode();
        balance.put("ledger_account_id", id.toString());
        balance.put("currency", values.get("currency").asString());
        balance.put("available_balance", values.has("overdraft_limit") ? values.get("overdraft_limit").asString() : "0");
        crud.insert("account_balance", balance);
        outbox.publish("ledger.ledger_account.created", "ledger_account", id, json.readTree(row));
        return row;
    }

    @Transactional
    public String changeStatus(UUID accountId, String newStatus, String reason) {
        String current = jdbc.sql("select status from ledger_account where id = ? for update").param(accountId)
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("ledger account", accountId));
        if ("CLOSED".equals(current)) {
            throw ApiException.conflict("account_closed", "Account " + accountId + " is closed");
        }
        if ("ACTIVE".equals(newStatus) && !FREEZE_STATES.contains(current)) {
            throw ApiException.conflict("not_frozen", "Account " + accountId + " is not frozen");
        }
        if (FREEZE_STATES.contains(newStatus) && !"ACTIVE".equals(current) && !FREEZE_STATES.contains(current)) {
            throw ApiException.conflict("invalid_transition", "Cannot freeze an account in status " + current);
        }
        if ("CLOSED".equals(newStatus)) {
            Map<String, Object> bal = jdbc.sql("""
                    select posted_balance, pending_debits, pending_credits from account_balance where ledger_account_id = ?""")
                    .param(accountId).query().singleRow();
            boolean empty = ((BigDecimal) bal.get("posted_balance")).signum() == 0
                    && ((BigDecimal) bal.get("pending_debits")).signum() == 0
                    && ((BigDecimal) bal.get("pending_credits")).signum() == 0;
            if (!empty) {
                throw ApiException.unprocessable("balance_not_zero", "Only accounts with zero balance and no holds can be closed");
            }
        }
        jdbc.sql("update ledger_account set status = ?, closed_at = case when ? then now() else closed_at end where id = ?")
                .params(newStatus, "CLOSED".equals(newStatus), accountId).update();
        String row = jdbc.sql("select row_to_json(a)::text from ledger_account a where id = ?").param(accountId)
                .query(String.class).single();
        ObjectNode payload = (ObjectNode) json.readTree(row);
        payload.put("reason", reason);
        outbox.publish("ledger.ledger_account.status_changed", "ledger_account", accountId, payload);
        return row;
    }

    @Transactional
    public String lockLedger(UUID ledgerId, String reason) {
        store.ledger(ledgerId);
        jdbc.sql("update ledger set status = 'LOCKED' where id = ?").param(ledgerId).update();
        return jdbc.sql("select row_to_json(l)::text from ledger l where id = ?").param(ledgerId).query(String.class).single();
    }
}
