package io.meridian.fx.api;

import io.meridian.fx.api.generated.FxDealsApi;
import io.meridian.fx.api.generated.model.FxDeal;
import io.meridian.fx.api.generated.model.FxDealsCancelRequest;
import io.meridian.fx.application.DealService;
import io.meridian.fx.infrastructure.Rows;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** FX deals: settlement (ledger posting per currency) and cancellation before settlement. */
@RestController
public class FxDealsController implements FxDealsApi {
    private final DealService deals;
    private final Actions actions;

    public FxDealsController(DealService deals, Actions actions) {
        this.deals = deals;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<FxDeal> fxDealsSettle(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "settle", null, FxDeal.class, () -> deals.settle(id));
    }

    @Override
    public ResponseEntity<FxDeal> fxDealsCancel(UUID id, String idempotencyKey, FxDealsCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, FxDeal.class,
                () -> deals.cancel(id, Rows.str(actions.tree(body), "reason")));
    }
}
