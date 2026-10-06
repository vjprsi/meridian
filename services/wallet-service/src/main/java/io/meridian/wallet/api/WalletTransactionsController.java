package io.meridian.wallet.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.wallet.api.generated.WalletTransactionsApi;
import io.meridian.wallet.api.generated.model.WalletTransaction;
import io.meridian.wallet.api.generated.model.WalletTransactionCreate;
import io.meridian.wallet.api.generated.model.WalletTransactionLegPage;
import io.meridian.wallet.api.generated.model.WalletTransactionsBankToWalletRequest;
import io.meridian.wallet.api.generated.model.WalletTransactionsCancelRequest;
import io.meridian.wallet.api.generated.model.WalletTransactionsCashInRequest;
import io.meridian.wallet.api.generated.model.WalletTransactionsCashOutRequest;
import io.meridian.wallet.api.generated.model.WalletTransactionsConfirmRequest;
import io.meridian.wallet.api.generated.model.WalletTransactionsP2pRequest;
import io.meridian.wallet.api.generated.model.WalletTransactionsQuoteRequest;
import io.meridian.wallet.api.generated.model.WalletTransactionsRequestReversalRequest;
import io.meridian.wallet.api.generated.model.WalletTransactionsReverseRequest;
import io.meridian.wallet.api.generated.model.WalletTransactionsWalletToBankRequest;
import io.meridian.wallet.application.PaymentFlows;
import io.meridian.wallet.application.TransactionEngine;
import io.meridian.wallet.application.WalletQueries;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Wallet transactions: every money movement of the wallet domain, posted through the ledger. */
@RestController
public class WalletTransactionsController implements WalletTransactionsApi {
    private final PaymentFlows flows;
    private final TransactionEngine engine;
    private final WalletQueries queries;
    private final Actions actions;
    private final CrudEngine crud;

    public WalletTransactionsController(PaymentFlows flows, TransactionEngine engine, WalletQueries queries,
                                        Actions actions, CrudEngine crud) {
        this.flows = flows;
        this.engine = engine;
        this.queries = queries;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<WalletTransaction> walletTransactionsCreate(String idempotencyKey, WalletTransactionCreate body) {
        return actions.create(idempotencyKey, body, WalletTransaction.class,
                () -> flows.create(actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<Map<String, Object>> walletTransactionsQuote(String idempotencyKey, WalletTransactionsQuoteRequest body) {
        return actions.objectAction(idempotencyKey, "quote", body, () -> flows.quote(actions.tree(body)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> walletTransactionsSummary(String txnType, String status, UUID merchantId,
                                                                         UUID agentId, UUID agentOutletId, UUID billerId,
                                                                         UUID walletId, OffsetDateTime from, OffsetDateTime to) {
        TenantContext.require();
        return actions.object(queries.summary(txnType, status, merchantId, agentId, agentOutletId, billerId, walletId, from, to));
    }

    @Override
    public ResponseEntity<WalletTransaction> walletTransactionsP2p(String idempotencyKey, WalletTransactionsP2pRequest body) {
        return actions.action(idempotencyKey, null, "p2p", body, WalletTransaction.class, () -> flows.p2p(actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<WalletTransaction> walletTransactionsCashIn(String idempotencyKey, WalletTransactionsCashInRequest body) {
        return actions.action(idempotencyKey, null, "cash-in", body, WalletTransaction.class, () -> flows.cashIn(actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<WalletTransaction> walletTransactionsCashOut(String idempotencyKey, WalletTransactionsCashOutRequest body) {
        return actions.action(idempotencyKey, null, "cash-out", body, WalletTransaction.class, () -> flows.cashOut(actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<WalletTransaction> walletTransactionsWalletToBank(String idempotencyKey,
                                                                           WalletTransactionsWalletToBankRequest body) {
        return actions.action(idempotencyKey, null, "wallet-to-bank", body, WalletTransaction.class, () -> flows.walletToBank(actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<WalletTransaction> walletTransactionsBankToWallet(String idempotencyKey,
                                                                           WalletTransactionsBankToWalletRequest body) {
        return actions.action(idempotencyKey, null, "bank-to-wallet", body, WalletTransaction.class, () -> flows.bankToWallet(actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<WalletTransaction> walletTransactionsConfirm(UUID id, String idempotencyKey,
                                                                      WalletTransactionsConfirmRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "confirm", body, WalletTransaction.class,
                () -> engine.confirm(id, Http.text(req, "pin"), Http.text(req, "otp")));
    }

    @Override
    public ResponseEntity<WalletTransaction> walletTransactionsCancel(UUID id, String idempotencyKey,
                                                                     WalletTransactionsCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, WalletTransaction.class,
                () -> engine.cancel(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<WalletTransaction> walletTransactionsRequestReversal(UUID id, String idempotencyKey,
                                                                              WalletTransactionsRequestReversalRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "request-reversal", body, WalletTransaction.class,
                () -> engine.requestReversal(id, Http.text(req, "reason"), Http.decimal(req, "amount")));
    }

    @Override
    public ResponseEntity<WalletTransaction> walletTransactionsReverse(UUID id, String idempotencyKey,
                                                                      WalletTransactionsReverseRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "reverse", body, WalletTransaction.class,
                () -> engine.reverse(id, Http.text(req, "reason"), Http.decimal(req, "amount")));
    }

    @Override
    public ResponseEntity<Map<String, Object>> walletTransactionsExpirePending(String idempotencyKey) {
        return actions.objectAction(idempotencyKey, "expire-pending", null, engine::expirePending);
    }

    @Override
    public ResponseEntity<WalletTransactionLegPage> walletTransactionsLegs(UUID id, Integer limit, String cursor) {
        crud.get("wallet_transaction", WalletTransaction.class, Crud.params().with("id", id));
        return crud.list("wallet_transaction_leg", WalletTransactionLegPage.class,
                Crud.params().with("wallet_transaction_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<String> walletTransactionsReceipt(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8))
                .body(queries.receipt(id));
    }
}
