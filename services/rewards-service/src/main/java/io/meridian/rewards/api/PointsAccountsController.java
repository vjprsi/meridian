package io.meridian.rewards.api;

import io.meridian.rewards.api.generated.PointsAccountsApi;
import io.meridian.rewards.api.generated.model.PointsAccount;
import io.meridian.rewards.api.generated.model.PointsAccountCreate;
import io.meridian.rewards.api.generated.model.PointsAccountsAdjustRequest;
import io.meridian.rewards.api.generated.model.PointsAccountsCloseRequest;
import io.meridian.rewards.api.generated.model.PointsAccountsReactivateRequest;
import io.meridian.rewards.api.generated.model.PointsAccountsSuspendRequest;
import io.meridian.rewards.api.generated.model.PointsAccountsTransferRequest;
import io.meridian.rewards.api.generated.model.PointsAdjustment;
import io.meridian.rewards.api.generated.model.PointsLedgerEntryPage;
import io.meridian.rewards.application.AccountService;
import io.meridian.rewards.application.Json;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Points accounts: enrolment, history, FIFO lots, adjustments, transfers, status and tiers. */
@RestController
public class PointsAccountsController implements PointsAccountsApi {
    private final AccountService accounts;
    private final Http http;
    private final CrudEngine crud;

    public PointsAccountsController(AccountService accounts, Http http, CrudEngine crud) {
        this.accounts = accounts;
        this.http = http;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<PointsAccount> pointsAccountsCreate(String idempotencyKey, PointsAccountCreate body) {
        return http.created(idempotencyKey, "account.create", body, PointsAccount.class, () -> accounts.create(body));
    }

    @Override
    public ResponseEntity<PointsLedgerEntryPage> pointsAccountsEntries(UUID id, Integer limit, String cursor) {
        crud.get("points_account", PointsAccount.class, Crud.params().with("id", id));
        return crud.list("points_ledger_entry", PointsLedgerEntryPage.class,
                Crud.params().with("points_account_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<Map<String, Object>> pointsAccountsLots(UUID id) {
        return http.query(accounts.lotsView(id));
    }

    @Override
    public ResponseEntity<PointsAdjustment> pointsAccountsAdjust(UUID id, String idempotencyKey, PointsAccountsAdjustRequest body) {
        var b = http.tree(body);
        long points = Json.lng(b, "points", 0);
        String reason = Json.required(b, "reason");
        return http.ok(idempotencyKey, "account.adjust", id, body, PointsAdjustment.class,
                () -> accounts.requestAdjustment(id, points, reason));
    }

    @Override
    public ResponseEntity<PointsAccount> pointsAccountsTransfer(UUID id, String idempotencyKey, PointsAccountsTransferRequest body) {
        var b = http.tree(body);
        UUID to = Json.uuid(b, "to_points_account_id");
        long points = Json.lng(b, "points", 0);
        String reason = Json.text(b, "reason");
        return http.ok(idempotencyKey, "account.transfer", id, body, PointsAccount.class,
                () -> accounts.transfer(id, to, points, reason));
    }

    @Override
    public ResponseEntity<PointsAccount> pointsAccountsSuspend(UUID id, String idempotencyKey, PointsAccountsSuspendRequest body) {
        String reason = Json.required(http.tree(body), "reason");
        return http.ok(idempotencyKey, "account.suspend", id, body, PointsAccount.class, () -> accounts.suspend(id, reason));
    }

    @Override
    public ResponseEntity<PointsAccount> pointsAccountsReactivate(UUID id, String idempotencyKey, PointsAccountsReactivateRequest body) {
        String reason = Json.text(http.tree(body), "reason");
        return http.ok(idempotencyKey, "account.reactivate", id, body, PointsAccount.class, () -> accounts.reactivate(id, reason));
    }

    @Override
    public ResponseEntity<PointsAccount> pointsAccountsClose(UUID id, String idempotencyKey, PointsAccountsCloseRequest body) {
        String reason = Json.required(http.tree(body), "reason");
        return http.ok(idempotencyKey, "account.close", id, body, PointsAccount.class, () -> accounts.close(id, reason));
    }

    @Override
    public ResponseEntity<PointsAccount> pointsAccountsRecalculateTier(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "account.tier", id, null, PointsAccount.class, () -> accounts.recalculateTier(id));
    }
}
