package io.meridian.pricingrules.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.pricingrules.api.generated.PriceQuotesApi;
import io.meridian.pricingrules.api.generated.model.ProductPriceQuote;
import io.meridian.pricingrules.api.generated.model.ProductPriceQuoteCreate;
import io.meridian.pricingrules.application.PriceQuoteService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Price quotes: validated issue (with event) and one-time acceptance before expiry. */
@RestController
public class PriceQuotesController implements PriceQuotesApi {
    private final PriceQuoteService quotes;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public PriceQuotesController(PriceQuoteService quotes, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.quotes = quotes;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<ProductPriceQuote> priceQuotesCreate(String idempotencyKey, ProductPriceQuoteCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, ProductPriceQuote.class, () -> {
            String row = quotes.create(body);
            return ApiSupport.withEtag(HttpStatus.CREATED, crud.read(row, ProductPriceQuote.class), json.readTree(row));
        });
    }

    @Override
    public ResponseEntity<ProductPriceQuote> priceQuotesAccept(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, ApiSupport.idemRequest("accept", id, null), ProductPriceQuote.class,
                () -> {
                    String row = quotes.accept(id);
                    return ApiSupport.withEtag(HttpStatus.OK, crud.read(row, ProductPriceQuote.class), json.readTree(row));
                });
    }
}
