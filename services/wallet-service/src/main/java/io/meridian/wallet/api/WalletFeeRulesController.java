package io.meridian.wallet.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.wallet.api.generated.WalletFeeRulesApi;
import io.meridian.wallet.api.generated.model.WalletFeeRule;
import io.meridian.wallet.api.generated.model.WalletFeeRuleCreate;
import io.meridian.wallet.api.generated.model.WalletFeeRuleUpdate;
import io.meridian.wallet.application.Tariffs;
import io.meridian.wallet.domain.FeeCalculator;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Customer fee rules; definitions are validated by the fee engine before they are stored. */
@RestController
public class WalletFeeRulesController implements WalletFeeRulesApi {
    private final CrudEngine crud;
    private final Actions actions;

    public WalletFeeRulesController(CrudEngine crud, Actions actions) {
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<WalletFeeRule> walletFeeRulesCreate(String idempotencyKey, WalletFeeRuleCreate body) {
        TenantContext.require();
        ObjectNode r = (ObjectNode) actions.tree(body);
        if (!r.hasNonNull("basis")) {
            r.put("basis", "FLAT");
        }
        FeeCalculator.validate(Tariffs.rule(r));
        return crud.create("wallet_fee_rule", WalletFeeRule.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<WalletFeeRule> walletFeeRulesUpdate(UUID id, String ifMatch, WalletFeeRuleUpdate body) {
        TenantContext.require();
        ObjectNode merged = (ObjectNode) actions.tree(crud.get("wallet_fee_rule", WalletFeeRule.class,
                Crud.params().with("id", id)).getBody());
        actions.tree(body).properties().forEach(e -> {
            if (!e.getValue().isNull()) {
                merged.set(e.getKey(), e.getValue());
            }
        });
        FeeCalculator.validate(Tariffs.rule(merged));
        return crud.update("wallet_fee_rule", WalletFeeRule.class, body, ifMatch, Crud.params().with("id", id));
    }
}
