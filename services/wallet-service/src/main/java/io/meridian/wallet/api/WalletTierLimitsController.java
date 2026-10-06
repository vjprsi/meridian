package io.meridian.wallet.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.wallet.api.generated.WalletTierLimitsApi;
import io.meridian.wallet.api.generated.model.WalletTierLimit;
import io.meridian.wallet.api.generated.model.WalletTierLimitCreate;
import io.meridian.wallet.api.generated.model.WalletTierLimitUpdate;
import io.meridian.wallet.infrastructure.Rows;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** KYC tier limits; amounts and counts must be non-negative and consistent (min <= max, daily <= monthly). */
@RestController
public class WalletTierLimitsController implements WalletTierLimitsApi {
    private final CrudEngine crud;
    private final Actions actions;

    public WalletTierLimitsController(CrudEngine crud, Actions actions) {
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<WalletTierLimit> walletTierLimitsCreate(String idempotencyKey, WalletTierLimitCreate body) {
        TenantContext.require();
        validate(actions.tree(body));
        return crud.create("wallet_tier_limit", WalletTierLimit.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<WalletTierLimit> walletTierLimitsUpdate(UUID id, String ifMatch, WalletTierLimitUpdate body) {
        TenantContext.require();
        ObjectNode merged = (ObjectNode) actions.tree(crud.get("wallet_tier_limit", WalletTierLimit.class,
                Crud.params().with("id", id)).getBody());
        actions.tree(body).properties().forEach(e -> {
            if (!e.getValue().isNull()) {
                merged.set(e.getKey(), e.getValue());
            }
        });
        validate(merged);
        return crud.update("wallet_tier_limit", WalletTierLimit.class, body, ifMatch, Crud.params().with("id", id));
    }

    static void validate(JsonNode l) {
        for (String f : new String[] {"max_balance", "per_txn_min", "per_txn_max", "daily_amount", "monthly_amount"}) {
            BigDecimal v = Rows.dec(l, f);
            if (v != null && v.signum() < 0) {
                throw ApiException.unprocessable("invalid_limit", f + " must not be negative");
            }
        }
        if (Rows.integer(l, "kyc_tier") != null && Rows.integer(l, "kyc_tier") < 0) {
            throw ApiException.unprocessable("invalid_limit", "kyc_tier must not be negative");
        }
        check(Rows.dec(l, "per_txn_min"), Rows.dec(l, "per_txn_max"), "per_txn_min must be <= per_txn_max");
        check(Rows.dec(l, "per_txn_max"), Rows.dec(l, "daily_amount"), "per_txn_max must be <= daily_amount");
        check(Rows.dec(l, "daily_amount"), Rows.dec(l, "monthly_amount"), "daily_amount must be <= monthly_amount");
        Integer dc = Rows.integer(l, "daily_count");
        Integer mc = Rows.integer(l, "monthly_count");
        if (dc != null && mc != null && dc > mc) {
            throw ApiException.unprocessable("invalid_limit", "daily_count must be <= monthly_count");
        }
        if (Rows.date(l, "valid_to") != null && Rows.date(l, "valid_from") != null
                && Rows.date(l, "valid_to").isBefore(Rows.date(l, "valid_from"))) {
            throw ApiException.unprocessable("invalid_limit", "valid_to must not be before valid_from");
        }
    }

    private static void check(BigDecimal low, BigDecimal high, String message) {
        if (low != null && high != null && low.compareTo(high) > 0) {
            throw ApiException.unprocessable("invalid_limit", message);
        }
    }
}
