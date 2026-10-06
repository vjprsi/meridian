package io.meridian.routing.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.routing.api.generated.NostroAccountsApi;
import io.meridian.routing.api.generated.model.NostroAccountsTopUpRequest;
import io.meridian.routing.api.generated.model.RailLiquidityPosition;
import io.meridian.routing.api.generated.model.RailLiquidityPositionPage;
import io.meridian.routing.application.LiquidityService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Nostro accounts: liquidity positions, refresh from the ledger, top-up from treasury. */
@RestController
public class NostroAccountsController implements NostroAccountsApi {
    private final LiquidityService liquidity;
    private final Actions actions;
    private final CrudEngine crud;

    public NostroAccountsController(LiquidityService liquidity, Actions actions, CrudEngine crud) {
        this.liquidity = liquidity;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<RailLiquidityPositionPage> nostroAccountsPositions(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        crud.findOne("nostro_account", Map.of("id", id)).orElseThrow(() -> ApiException.notFound("nostro account", id));
        return crud.list("rail_liquidity_position", RailLiquidityPositionPage.class,
                Crud.params().with("nostro_account_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<RailLiquidityPosition> nostroAccountsRefreshPosition(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "refresh-position", null, RailLiquidityPosition.class,
                () -> liquidity.refresh(id));
    }

    @Override
    public ResponseEntity<RailLiquidityPosition> nostroAccountsTopUp(UUID id, String idempotencyKey,
                                                                    NostroAccountsTopUpRequest body) {
        JsonNode req = actions.tree(body);
        BigDecimal amount = Http.decimal(req, "amount");
        return actions.action(idempotencyKey, id, "top-up", body, RailLiquidityPosition.class,
                () -> liquidity.topUp(id, amount, Http.text(req, "reason"), idempotencyKey));
    }
}
