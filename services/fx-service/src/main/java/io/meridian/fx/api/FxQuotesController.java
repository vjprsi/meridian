package io.meridian.fx.api;

import io.meridian.fx.api.generated.FxQuotesApi;
import io.meridian.fx.api.generated.model.FxDeal;
import io.meridian.fx.api.generated.model.FxQuote;
import io.meridian.fx.api.generated.model.FxQuoteCreate;
import io.meridian.fx.api.generated.model.FxQuotesAcceptRequest;
import io.meridian.fx.application.QuoteService;
import io.meridian.fx.infrastructure.Rows;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** FX quotes: priced creation (margin rules, expiry), acceptance into a deal, cancellation. */
@RestController
public class FxQuotesController implements FxQuotesApi {
    private final QuoteService quotes;
    private final Actions actions;

    public FxQuotesController(QuoteService quotes, Actions actions) {
        this.quotes = quotes;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<FxQuote> fxQuotesCreate(String idempotencyKey, FxQuoteCreate body) {
        return actions.create(idempotencyKey, body, FxQuote.class, () -> quotes.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<FxDeal> fxQuotesAccept(UUID id, String idempotencyKey, FxQuotesAcceptRequest body) {
        return actions.action(idempotencyKey, id, "accept", body, FxDeal.class,
                () -> quotes.accept(id, actions.tree(body)));
    }

    @Override
    public ResponseEntity<FxQuote> fxQuotesCancel(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "cancel", null, FxQuote.class, () -> quotes.cancel(id));
    }
}
