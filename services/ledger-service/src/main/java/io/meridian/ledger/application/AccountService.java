package io.meridian.ledger.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.ledger.domain.AccountState;
import io.meridian.ledger.infrastructure.LedgerStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ledger account lifecycle: open (with its balance row), freeze/unfreeze (idempotent), overdraft limit, close;
 * ledger locking.
 */
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
        if (newStatus.equals(current)) {
            // Idempotent: already in the target state (freeze to the same restriction, unfreeze an ACTIVE account).
            return accountJson(accountId);
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
        jdbc.sql("update ledger_account set status = ?, closed_at = case when ? then now() else closed_at end, "
                        + "updated_by = ? where id = ?")
                .params(newStatus, "CLOSED".equals(newStatus), TenantContext.principal(), accountId).update();
        String row = accountJson(accountId);
        ObjectNode payload = (ObjectNode) json.readTree(row);
        payload.put("reason", reason);
        payload.put("previous_status", current);
        outbox.publish("ledger.ledger_account.status_changed", "ledger_account", accountId, payload);
        return row;
    }

    /**
     * Sets the arranged overdraft limit and recomputes the stored available balance in the same transaction (the
     * balance row is locked like a posting would). Idempotent: the same limit returns the account unchanged.
     */
    @Transactional
    public String setOverdraftLimit(UUID accountId, BigDecimal limit, String reason) {
        if (limit == null || limit.signum() < 0) {
            throw ApiException.unprocessable("invalid_overdraft_limit", "limit must be zero or positive");
        }
        requireAccount(accountId);
        AccountState a = store.lockAccounts(List.of(accountId)).get(accountId);
        if ("CLOSED".equals(a.status)) {
            throw ApiException.conflict("account_closed", "Account " + accountId + " is closed");
        }
        if (a.overdraftLimit.compareTo(limit) == 0) {
            return accountJson(accountId);
        }
        jdbc.sql("update ledger_account set overdraft_limit = ?, updated_by = ? where id = ?")
                .params(limit, TenantContext.principal(), accountId).update();
        refreshAvailable(accountId);
        String row = accountJson(accountId);
        ObjectNode payload = (ObjectNode) json.readTree(row);
        payload.put("previous_overdraft_limit", a.overdraftLimit.toPlainString());
        if (reason != null) {
            payload.put("reason", reason);
        }
        outbox.publish("ledger.ledger_account.overdraft_limit_changed", "ledger_account", accountId, payload);
        return row;
    }

    /**
     * PATCH: generic column update; an overdraft_limit change goes through the same rules as the action so the
     * stored available balance never goes stale.
     */
    @Transactional
    public String update(UUID accountId, Integer expectedVersion, Object body) {
        ObjectNode values = crud.toColumns("ledger_account", body);
        values.remove("status");
        BigDecimal limit = values.hasNonNull("overdraft_limit") ? new BigDecimal(values.get("overdraft_limit").asString()) : null;
        if (limit != null && limit.signum() < 0) {
            throw ApiException.unprocessable("invalid_overdraft_limit", "overdraft_limit must be zero or positive");
        }
        requireAccount(accountId);
        AccountState before = store.lockAccounts(List.of(accountId)).get(accountId);
        String row = crud.updateRow("ledger_account", java.util.Map.of("id", accountId), values, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match " + expectedVersion + " does not match the current version; reload and retry"));
        if (limit != null && before.overdraftLimit.compareTo(limit) != 0) {
            refreshAvailable(accountId);
            ObjectNode payload = (ObjectNode) json.readTree(row);
            payload.put("previous_overdraft_limit", before.overdraftLimit.toPlainString());
            outbox.publish("ledger.ledger_account.overdraft_limit_changed", "ledger_account", accountId, payload);
        }
        return row;
    }

    private void refreshAvailable(UUID accountId) {
        jdbc.sql("""
                update account_balance b set available_balance = b.posted_balance - b.pending_debits + a.overdraft_limit
                from ledger_account a where a.id = b.ledger_account_id and b.ledger_account_id = ?""")
                .param(accountId).update();
    }

    private void requireAccount(UUID accountId) {
        jdbc.sql("select 1 from ledger_account where id = ?").param(accountId).query(Integer.class).optional()
                .orElseThrow(() -> ApiException.notFound("ledger account", accountId));
    }

    private String accountJson(UUID accountId) {
        return jdbc.sql("select row_to_json(a)::text from ledger_account a where id = ?").param(accountId)
                .query(String.class).single();
    }

    @Transactional
    public String lockLedger(UUID ledgerId, String reason) {
        store.ledger(ledgerId);
        jdbc.sql("update ledger set status = 'LOCKED' where id = ?").param(ledgerId).update();
        return jdbc.sql("select row_to_json(l)::text from ledger l where id = ?").param(ledgerId).query(String.class).single();
    }
}
