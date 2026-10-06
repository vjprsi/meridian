package io.meridian.card.api;

import io.meridian.card.api.generated.CardProductsApi;
import io.meridian.card.api.generated.model.CardProduct;
import io.meridian.card.api.generated.model.CardProductCreate;
import io.meridian.card.api.generated.model.CardProductUpdate;
import io.meridian.card.api.generated.model.CardProductsRetireRequest;
import io.meridian.card.application.ProgramService;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Card products: validation against the program, activation and retirement. */
@RestController
public class CardProductsController implements CardProductsApi {
    private final ProgramService programs;
    private final Actions actions;
    private final CrudEngine crud;
    private final TransactionTemplate tx;

    public CardProductsController(ProgramService programs, Actions actions, CrudEngine crud, TransactionTemplate tx) {
        this.programs = programs;
        this.actions = actions;
        this.crud = crud;
        this.tx = tx;
    }

    @Override
    public ResponseEntity<CardProduct> cardProductsCreate(String idempotencyKey, CardProductCreate body) {
        return actions.create(idempotencyKey, body, CardProduct.class, () -> programs.createProduct(actions.tree(body)));
    }

    @Override
    public ResponseEntity<CardProduct> cardProductsUpdate(UUID id, String ifMatch, CardProductUpdate body) {
        TenantContext.require();
        JsonNode req = actions.tree(body);
        if (req.hasNonNull("card_program_id")) {
            throw ApiException.unprocessable("field_not_updatable", "card_program_id cannot be changed");
        }
        return tx.execute(s -> {
            programs.checkProductUpdate(id, req);
            return crud.update("card_product", CardProduct.class, body, ifMatch, Crud.params().with("id", id));
        });
    }

    @Override
    public ResponseEntity<CardProduct> cardProductsActivate(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "activate", null, CardProduct.class,
                () -> programs.productTransition(id, "activate", null));
    }

    @Override
    public ResponseEntity<CardProduct> cardProductsRetire(UUID id, String idempotencyKey, CardProductsRetireRequest body) {
        return actions.action(idempotencyKey, id, "retire", body, CardProduct.class,
                () -> programs.productTransition(id, "retire", Http.text(actions.tree(body), "reason")));
    }
}
