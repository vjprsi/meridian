package io.meridian.treasurydeal.api;

import io.meridian.treasurydeal.api.generated.SecuritiesTradesApi;
import io.meridian.treasurydeal.api.generated.model.SecuritiesTrade;
import io.meridian.treasurydeal.api.generated.model.SecuritiesTradeCreate;
import io.meridian.treasurydeal.api.generated.model.SecuritiesTradesCancelRequest;
import io.meridian.treasurydeal.application.SecuritiesService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Securities trades: capture (priced, holding resolved), settlement and cancellation. */
@RestController
public class SecuritiesTradesController implements SecuritiesTradesApi {
    private final SecuritiesService service;
    private final Actions actions;

    public SecuritiesTradesController(SecuritiesService service, Actions actions) {
        this.service = service;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<SecuritiesTrade> securitiesTradesCreate(String idempotencyKey, SecuritiesTradeCreate body) {
        return actions.create(idempotencyKey, body, SecuritiesTrade.class, () -> service.captureTrade(actions.tree(body)));
    }

    @Override
    public ResponseEntity<SecuritiesTrade> securitiesTradesSettle(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "settle", null, SecuritiesTrade.class, () -> service.settleTrade(id));
    }

    @Override
    public ResponseEntity<SecuritiesTrade> securitiesTradesCancel(UUID id, String idempotencyKey, SecuritiesTradesCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, SecuritiesTrade.class,
                () -> service.cancelTrade(id, Http.text(actions.tree(body), "reason")));
    }
}
