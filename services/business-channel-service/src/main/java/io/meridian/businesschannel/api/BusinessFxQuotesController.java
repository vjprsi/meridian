package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessFxQuotesApi;
import io.meridian.businesschannel.api.generated.model.BusinessFxQuotesCreateRequest;
import io.meridian.businesschannel.api.generated.model.FxQuote;
import io.meridian.businesschannel.api.generated.model.FxQuotePage;
import io.meridian.businesschannel.application.PayeeFxService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** FX quotes priced for the business. */
@RestController
public class BusinessFxQuotesController implements BusinessFxQuotesApi {
    private final PayeeFxService fx;
    private final Http http;

    public BusinessFxQuotesController(PayeeFxService fx, Http http) {
        this.fx = fx;
        this.http = http;
    }

    @Override
    public ResponseEntity<FxQuotePage> businessFxQuotesList(Integer limit, String cursor) {
        return http.ok(fx.quotes(limit, cursor), FxQuotePage.class);
    }

    @Override
    public ResponseEntity<FxQuote> businessFxQuotesCreate(String idempotencyKey, BusinessFxQuotesCreateRequest body) {
        return http.ok(fx.createQuote(http.body(body), idempotencyKey), FxQuote.class);
    }

    @Override
    public ResponseEntity<FxQuote> businessFxQuotesGet(UUID quoteId) {
        return http.ok(fx.quote(quoteId), FxQuote.class);
    }
}
