package io.meridian.wallet.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.wallet.api.generated.WalletsApi;
import io.meridian.wallet.api.generated.model.AccountBalance;
import io.meridian.wallet.api.generated.model.Wallet;
import io.meridian.wallet.api.generated.model.WalletCreate;
import io.meridian.wallet.api.generated.model.WalletStatusHistoryPage;
import io.meridian.wallet.api.generated.model.WalletTransactionPage;
import io.meridian.wallet.api.generated.model.WalletUpdate;
import io.meridian.wallet.api.generated.model.WalletsActivateRequest;
import io.meridian.wallet.api.generated.model.WalletsBlockRequest;
import io.meridian.wallet.api.generated.model.WalletsCloseRequest;
import io.meridian.wallet.api.generated.model.WalletsReactivateRequest;
import io.meridian.wallet.api.generated.model.WalletsSetPinRequest;
import io.meridian.wallet.api.generated.model.WalletsStatementsRequest;
import io.meridian.wallet.api.generated.model.WalletsSuspendRequest;
import io.meridian.wallet.api.generated.model.WalletsUpgradeTierRequest;
import io.meridian.wallet.api.generated.model.WalletsVerifyPinRequest;
import io.meridian.wallet.application.PinGuard;
import io.meridian.wallet.application.WalletQueries;
import io.meridian.wallet.application.WalletService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Wallets: lifecycle, PINs, balances and statements from the ledger. */
@RestController
public class WalletsController implements WalletsApi {
    private static final Set<String> PROTECTED = Set.of("wallet_type", "currency", "customer_id", "agent_id",
            "merchant_id", "kyc_tier", "msisdn");

    private final WalletService wallets;
    private final WalletQueries queries;
    private final PinGuard pins;
    private final Actions actions;
    private final CrudEngine crud;

    public WalletsController(WalletService wallets, WalletQueries queries, PinGuard pins, Actions actions, CrudEngine crud) {
        this.wallets = wallets;
        this.queries = queries;
        this.pins = pins;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<Wallet> walletsCreate(String idempotencyKey, WalletCreate body) {
        return actions.create(idempotencyKey, body, Wallet.class, () -> wallets.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<Wallet> walletsUpdate(UUID id, String ifMatch, WalletUpdate body) {
        return actions.guardedUpdate(crud, "wallet", Wallet.class, id, ifMatch, body, PROTECTED,
                "use upgrade-tier for tiers; owner, type, currency and mobile number are fixed");
    }

    @Override
    public ResponseEntity<Wallet> walletsLookup(String msisdn, String alias, String walletNumber, String currency) {
        TenantContext.require();
        return ResponseEntity.ok(actions.model(queries.lookup(msisdn, alias, walletNumber, currency), Wallet.class));
    }

    @Override
    public ResponseEntity<AccountBalance> walletsBalance(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok(actions.model(queries.balance(id), AccountBalance.class));
    }

    @Override
    public ResponseEntity<WalletTransactionPage> walletsTransactions(UUID id, OffsetDateTime from, OffsetDateTime to,
                                                                     String txnType, String status, Integer limit,
                                                                     String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(actions.json().readValue(
                queries.transactionsPage(id, from, to, txnType, status, limit, cursor), WalletTransactionPage.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> walletsLimits(UUID id, String txnType) {
        TenantContext.require();
        return actions.object(queries.limits(id, txnType));
    }

    @Override
    public ResponseEntity<WalletStatusHistoryPage> walletsStatusHistory(UUID id, Integer limit, String cursor) {
        crud.get("wallet", Wallet.class, Crud.params().with("id", id));
        return crud.list("wallet_status_history", WalletStatusHistoryPage.class,
                Crud.params().with("wallet_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<Map<String, Object>> walletsStatement(UUID id, LocalDate from, LocalDate to) {
        TenantContext.require();
        return actions.object(queries.statement(id, from, to));
    }

    @Override
    public ResponseEntity<Map<String, Object>> walletsStatements(UUID id, String idempotencyKey, WalletsStatementsRequest body) {
        JsonNode req = actions.tree(body);
        return actions.objectAction(idempotencyKey, "statements:" + id, body, () -> queries.requestStatement(id,
                LocalDate.parse(Http.text(req, "from_date")), LocalDate.parse(Http.text(req, "to_date")),
                Http.text(req, "format"), idempotencyKey));
    }

    @Override
    public ResponseEntity<Wallet> walletsUpgradeTier(UUID id, String idempotencyKey, WalletsUpgradeTierRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "upgrade-tier", body, Wallet.class,
                () -> wallets.upgradeTier(id, req.get("kyc_tier").asInt(), Http.uuid(req, "kyc_profile_id")));
    }

    @Override
    public ResponseEntity<Wallet> walletsActivate(UUID id, String idempotencyKey, WalletsActivateRequest body) {
        return actions.action(idempotencyKey, id, "activate", body, Wallet.class,
                () -> wallets.activate(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<Wallet> walletsSuspend(UUID id, String idempotencyKey, WalletsSuspendRequest body) {
        return actions.action(idempotencyKey, id, "suspend", body, Wallet.class,
                () -> wallets.suspend(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<Wallet> walletsBlock(UUID id, String idempotencyKey, WalletsBlockRequest body) {
        return actions.action(idempotencyKey, id, "block", body, Wallet.class,
                () -> wallets.block(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<Wallet> walletsReactivate(UUID id, String idempotencyKey, WalletsReactivateRequest body) {
        return actions.action(idempotencyKey, id, "reactivate", body, Wallet.class,
                () -> wallets.reactivate(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<Wallet> walletsClose(UUID id, String idempotencyKey, WalletsCloseRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "close", body, Wallet.class,
                () -> wallets.close(id, Http.text(req, "reason"), Http.uuid(req, "sweep_to_wallet_id")));
    }

    @Override
    public ResponseEntity<Wallet> walletsSetPin(UUID id, String idempotencyKey, WalletsSetPinRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "set-pin", body, Wallet.class,
                () -> wallets.setPin(id, Http.text(req, "new_pin"), Http.text(req, "current_pin")));
    }

    @Override
    public ResponseEntity<Map<String, Object>> walletsVerifyPin(UUID id, String idempotencyKey, WalletsVerifyPinRequest body) {
        JsonNode req = actions.tree(body);
        return actions.objectAction(idempotencyKey, "verify-pin:" + id, body, () -> {
            actions.json(); // tenant checked by objectAction
            return actions.json().valueToTree(pins.verify(id, Http.text(req, "pin")).asMap());
        });
    }

    @Override
    public ResponseEntity<Map<String, Object>> walletsEodDormancy(LocalDate businessDate, String idempotencyKey) {
        return actions.objectAction(idempotencyKey, "eod-dormancy", Map.of("business_date", businessDate.toString()),
                () -> wallets.markDormant(businessDate));
    }
}
