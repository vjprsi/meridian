package io.meridian.merchant.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.merchant.api.generated.MerchantsApi;
import io.meridian.merchant.api.generated.model.Merchant;
import io.meridian.merchant.api.generated.model.MerchantCreate;
import io.meridian.merchant.api.generated.model.MerchantSettlementPage;
import io.meridian.merchant.api.generated.model.MerchantStorePage;
import io.meridian.merchant.api.generated.model.MerchantUpdate;
import io.meridian.merchant.api.generated.model.MerchantsActivateRequest;
import io.meridian.merchant.api.generated.model.MerchantsSuspendRequest;
import io.meridian.merchant.api.generated.model.MerchantsTerminateRequest;
import io.meridian.merchant.api.generated.model.WalletTransactionPage;
import io.meridian.merchant.application.MerchantService;
import io.meridian.merchant.infrastructure.WalletClient;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Merchants: onboarding, activation (wallet opened), suspension, termination and views. */
@RestController
public class MerchantsController implements MerchantsApi {
    private static final Set<String> PROTECTED = Set.of("currency", "country", "customer_id", "merchant_type");

    private final MerchantService merchants;
    private final WalletClient wallets;
    private final Actions actions;
    private final CrudEngine crud;

    public MerchantsController(MerchantService merchants, WalletClient wallets, Actions actions, CrudEngine crud) {
        this.merchants = merchants;
        this.wallets = wallets;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<Merchant> merchantsCreate(String idempotencyKey, MerchantCreate body) {
        return actions.create(idempotencyKey, body, Merchant.class, () -> merchants.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<Merchant> merchantsUpdate(UUID id, String ifMatch, MerchantUpdate body) {
        JsonNode req = actions.tree(body);
        if (req.hasNonNull("mcc") && !req.get("mcc").asString().matches("\\d{4}")) {
            throw io.meridian.common.error.ApiException.unprocessable("invalid_mcc", "mcc must be 4 digits");
        }
        return actions.guardedUpdate(crud, "merchant", Merchant.class, id, ifMatch, body, PROTECTED,
                "currency, country, type and owner are fixed at onboarding");
    }

    @Override
    public ResponseEntity<Merchant> merchantsActivate(UUID id, String idempotencyKey, MerchantsActivateRequest body) {
        return actions.action(idempotencyKey, id, "activate", body, Merchant.class,
                () -> merchants.activate(id, Http.text(actions.tree(body), "comment")));
    }

    @Override
    public ResponseEntity<Merchant> merchantsSuspend(UUID id, String idempotencyKey, MerchantsSuspendRequest body) {
        return actions.action(idempotencyKey, id, "suspend", body, Merchant.class,
                () -> merchants.suspend(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<Merchant> merchantsTerminate(UUID id, String idempotencyKey, MerchantsTerminateRequest body) {
        return actions.action(idempotencyKey, id, "terminate", body, Merchant.class,
                () -> merchants.terminate(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<MerchantStorePage> merchantsStores(UUID id, Integer limit, String cursor) {
        crud.get("merchant", Merchant.class, Crud.params().with("id", id));
        return crud.list("merchant_store", MerchantStorePage.class,
                Crud.params().with("merchant_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<MerchantSettlementPage> merchantsSettlements(UUID id, Integer limit, String cursor) {
        crud.get("merchant", Merchant.class, Crud.params().with("id", id));
        return crud.list("merchant_settlement", MerchantSettlementPage.class,
                Crud.params().with("merchant_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<WalletTransactionPage> merchantsTransactions(UUID id, OffsetDateTime from, OffsetDateTime to,
                                                                       Integer limit, String cursor) {
        TenantContext.require();
        crud.get("merchant", Merchant.class, Crud.params().with("id", id));
        JsonNode page = wallets.page(id, null, null, from == null ? null : from.toString(),
                to == null ? null : to.toString(), limit, cursor);
        return ResponseEntity.ok(actions.model(page, WalletTransactionPage.class));
    }
}
