package io.meridian.ledger.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.ledger.api.generated.LedgerAccountsApi;
import io.meridian.ledger.api.generated.model.AccountBalance;
import io.meridian.ledger.api.generated.model.BalanceSnapshotPage;
import io.meridian.ledger.api.generated.model.LedgerAccount;
import io.meridian.ledger.api.generated.model.LedgerAccountCreate;
import io.meridian.ledger.api.generated.model.LedgerAccountsCloseRequest;
import io.meridian.ledger.api.generated.model.LedgerAccountsFreezeRequest;
import io.meridian.ledger.api.generated.model.LedgerAccountsUnfreezeRequest;
import io.meridian.ledger.api.generated.model.LedgerEntryPage;
import io.meridian.ledger.application.AccountService;
import io.meridian.ledger.infrastructure.LedgerStore;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Ledger accounts: open with a balance row, balances (current and point-in-time), entries, status changes. */
@RestController
public class LedgerAccountsController implements LedgerAccountsApi {
    private final AccountService accounts;
    private final LedgerStore store;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public LedgerAccountsController(AccountService accounts, LedgerStore store, CrudEngine crud,
                                    Idempotency idempotency, JdbcClient jdbc, JsonMapper json) {
        this.accounts = accounts;
        this.store = store;
        this.crud = crud;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public ResponseEntity<LedgerAccount> ledgerAccountsCreate(String idempotencyKey, LedgerAccountCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, LedgerAccount.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(crud.read(accounts.open(body), LedgerAccount.class)));
    }

    @Override
    public ResponseEntity<AccountBalance> ledgerAccountsBalance(UUID id) {
        TenantContext.require();
        String row = store.balanceJson(id).orElseThrow(() -> ApiException.notFound("ledger account", id));
        return ResponseEntity.ok(crud.read(row, AccountBalance.class));
    }

    /** Posted balance as booked at {@code at}: the balance_after of the last entry booked at or before it. */
    @Override
    public ResponseEntity<AccountBalance> ledgerAccountsBalanceAt(UUID id, OffsetDateTime at) {
        TenantContext.require();
        String current = store.balanceJson(id).orElseThrow(() -> ApiException.notFound("ledger account", id));
        JsonNode cur = json.readTree(current);
        String row = jdbc.sql("""
                select json_build_object('id', ?::uuid, 'ledger_account_id', ?::uuid, 'currency', ?,
                       'posted_balance', coalesce(e.balance_after, 0), 'posted_debits', 0, 'posted_credits', 0,
                       'pending_debits', 0, 'pending_credits', 0, 'available_balance', coalesce(e.balance_after, 0),
                       'account_version', coalesce(e.account_version, 0), 'last_entry_at', e.created_at,
                       'created_at', now(), 'updated_at', now(), 'row_version', 1)::text
                from (select 1) one left join lateral (
                    select balance_after, account_version, created_at from ledger_entry
                    where ledger_account_id = ? and created_at <= ? order by account_version desc limit 1) e on true""")
                .params(cur.get("id").asString(), id, cur.get("currency").asString(), id, at)
                .query(String.class).single();
        return ResponseEntity.ok(crud.read(row, AccountBalance.class));
    }

    @Override
    public ResponseEntity<BalanceSnapshotPage> ledgerAccountsBalanceHistory(UUID id, LocalDate from, LocalDate to,
                                                                           Integer limit, String cursor) {
        TenantContext.require();
        var rows = jdbc.sql("""
                select row_to_json(s)::text from balance_snapshot s
                where ledger_account_id = ? and (?::date is null or as_of_date >= ?::date)
                  and (?::date is null or as_of_date <= ?::date)
                order by as_of_date desc limit 1000""")
                .params(id, from, from, to, to).query(String.class).list();
        return ResponseEntity.ok(json.readValue("{\"data\":[" + String.join(",", rows) + "],\"has_more\":false}",
                BalanceSnapshotPage.class));
    }

    @Override
    public ResponseEntity<LedgerEntryPage> ledgerAccountsEntries(UUID id, OffsetDateTime from, OffsetDateTime to,
                                                                 Integer limit, String cursor) {
        return crud.list("ledger_entry", LedgerEntryPage.class, Crud.params()
                .with("ledger_account_id", id).with("created_after", from).with("created_before", to)
                .with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<LedgerAccount> ledgerAccountsFreeze(UUID id, String idempotencyKey,
                                                              LedgerAccountsFreezeRequest body) {
        JsonNode req = json.valueToTree(body);
        return ok(accounts.changeStatus(id, req.get("status").asString(), req.get("reason").asString()));
    }

    @Override
    public ResponseEntity<LedgerAccount> ledgerAccountsUnfreeze(UUID id, String idempotencyKey,
                                                                LedgerAccountsUnfreezeRequest body) {
        return ok(accounts.changeStatus(id, "ACTIVE", json.valueToTree(body).get("reason").asString()));
    }

    @Override
    public ResponseEntity<LedgerAccount> ledgerAccountsClose(UUID id, String idempotencyKey,
                                                             LedgerAccountsCloseRequest body) {
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return ok(accounts.changeStatus(id, "CLOSED", req.hasNonNull("reason") ? req.get("reason").asString() : null));
    }

    private ResponseEntity<LedgerAccount> ok(String row) {
        return ResponseEntity.ok(crud.read(row, LedgerAccount.class));
    }
}
