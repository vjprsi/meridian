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
    private final org.springframework.core.env.Environment env;

    public AccountService(LedgerStore store, CrudEngine crud, OutboxWriter outbox, JdbcClient jdbc, JsonMapper json,
                         org.springframework.core.env.Environment env) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.json = json;
        this.env = env;
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
    public String setOverdraftLimit(UUID accountId, BigDecimal limit, Boolean allowNegative, String reason) {
        if (limit == null || limit.signum() < 0) {
            throw ApiException.unprocessable("invalid_overdraft_limit", "limit must be zero or positive");
        }
        String owner = requireAccount(accountId);
        requireOwningService(accountId, owner);
        AccountState a = store.lockAccounts(List.of(accountId)).get(accountId);
        if ("CLOSED".equals(a.status)) {
            throw ApiException.conflict("account_closed", "Account " + accountId + " is closed");
        }
        boolean negative = allowNegative == null ? a.allowNegative : allowNegative;
        if (a.overdraftLimit.compareTo(limit) == 0 && a.allowNegative == negative) {
            return accountJson(accountId);
        }
        jdbc.sql("update ledger_account set overdraft_limit = ?, allow_negative = ?, updated_by = ? where id = ?")
                .params(limit, negative, TenantContext.principal(), accountId).update();
        refreshAvailable(accountId);
        String row = accountJson(accountId);
        ObjectNode payload = (ObjectNode) json.readTree(row);
        payload.put("previous_overdraft_limit", a.overdraftLimit.toPlainString());
        payload.put("previous_allow_negative", a.allowNegative);
        TenantContext.callingService().ifPresent(s -> payload.put("changed_by_service", s));
        if (reason != null) {
            payload.put("reason", reason);
        }
        outbox.publish("ledger.ledger_account.overdraft_limit_changed", "ledger_account", accountId, payload);
        return row;
    }

    /**
     * SEC-12: credit limits are changed only by the service that owns the account (deposit-service once an overdraft
     * facility is approved through maker-checker), never by a user token. Owner types are mapped to their service by
     * {@code meridian.ledger.account-owners.<OWNER_TYPE>}; accounts of other owner types (internal / GL accounts)
     * accept any Meridian service identity.
     */
    private void requireOwningService(UUID accountId, String ownerType) {
        String caller = TenantContext.callingService().orElseThrow(() -> new ApiException(403,
                "service_identity_required", "The overdraft limit of ledger account " + accountId
                + " can only be changed by its owning service (approved overdraft facility), not by a user"));
        String owning = ownerType == null ? null : env.getProperty("meridian.ledger.account-owners." + ownerType);
        if (owning != null && !owning.equals(caller)) {
            throw new ApiException(403, "not_account_owner",
                    "Ledger account " + accountId + " (" + ownerType + ") is owned by " + owning + ", not " + caller);
        }
    }

    /** Columns that never change through PATCH (SEC-12): identity of the account and its credit terms. */
    private static final List<String> NOT_PATCHABLE = List.of("ledger_id", "currency", "account_type", "normal_balance",
            "owner_type", "owner_id", "overdraft_limit", "allow_negative", "min_balance", "opened_at", "closed_at");

    /**
     * PATCH: descriptive fields only (name, code, parent, GL mapping, metadata). Currency, ledger, accounting class,
     * owner and credit terms are immutable here: a value that differs from the stored one is 422
     * {@code field_not_updatable}; the overdraft limit / allow_negative change only through the owning service's
     * {@code POST /{id}/overdraft-limit}.
     */
    @Transactional
    public String update(UUID accountId, Integer expectedVersion, Object body) {
        ObjectNode values = crud.toColumns("ledger_account", body);
        values.remove("status");
        requireAccount(accountId);
        ObjectNode current = (ObjectNode) json.readTree(accountJson(accountId));
        for (String field : NOT_PATCHABLE) {
            if (values.has(field)) {
                if (!sameValue(current.get(field), values.get(field))) {
                    throw new ApiException(422, "field_not_updatable", field + " cannot be changed on a ledger account"
                            + (field.equals("overdraft_limit") || field.equals("allow_negative")
                            ? " by PATCH; it is set by the owning service through POST /v1/ledger-accounts/{id}/overdraft-limit"
                            : " after creation"));
                }
                values.remove(field);
            }
        }
        return crud.updateRow("ledger_account", java.util.Map.of("id", accountId), values, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match " + expectedVersion + " does not match the current version; reload and retry"));
    }

    private static boolean sameValue(tools.jackson.databind.JsonNode stored, tools.jackson.databind.JsonNode given) {
        boolean storedNull = stored == null || stored.isNull();
        boolean givenNull = given == null || given.isNull();
        if (storedNull || givenNull) {
            return storedNull == givenNull;
        }
        if (stored.isNumber() || given.isNumber()) {
            try {
                return new BigDecimal(stored.asString()).compareTo(new BigDecimal(given.asString())) == 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (stored.isBoolean() || given.isBoolean()) {
            return stored.asString().equalsIgnoreCase(given.asString());
        }
        String a = stored.asString().trim();
        String b = given.asString().trim();
        if (a.equals(b)) {
            return true;
        }
        try { // timestamps in different offsets / precision
            return java.time.OffsetDateTime.parse(a).toInstant().equals(java.time.OffsetDateTime.parse(b).toInstant());
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    private void refreshAvailable(UUID accountId) {
        jdbc.sql("""
                update account_balance b set available_balance = b.posted_balance - b.pending_debits + a.overdraft_limit
                from ledger_account a where a.id = b.ledger_account_id and b.ledger_account_id = ?""")
                .param(accountId).update();
    }

    /** Returns the account's owner_type (may be null); 404 when the account does not exist. */
    private String requireAccount(UUID accountId) {
        String owner = jdbc.sql("select coalesce(owner_type, '') from ledger_account where id = ?").param(accountId)
                .query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("ledger account", accountId));
        return owner.isEmpty() ? null : owner;
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
