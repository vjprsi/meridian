package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessCardProductsApi;
import io.meridian.businesschannel.api.generated.model.CardProductPage;
import io.meridian.businesschannel.application.CardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Card products expense cards can be issued on. */
@RestController
public class BusinessCardProductsController implements BusinessCardProductsApi {
    private final CardService cards;
    private final Http http;

    public BusinessCardProductsController(CardService cards, Http http) {
        this.cards = cards;
        this.http = http;
    }

    @Override
    public ResponseEntity<CardProductPage> businessCardProductsList(Integer limit, String cursor) {
        return http.ok(cards.products(limit, cursor), CardProductPage.class);
    }
}
