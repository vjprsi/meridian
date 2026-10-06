package io.meridian.card.api;

import io.meridian.card.api.generated.CardBureausApi;
import io.meridian.card.api.generated.model.CardBureau;
import io.meridian.card.api.generated.model.CardBureauCreate;
import io.meridian.card.api.generated.model.CardBureauUpdate;
import io.meridian.card.application.ProgramService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Card bureaus: generic CRUD with cut-off time validation. */
@RestController
public class CardBureausController implements CardBureausApi {
    private final CrudEngine crud;
    private final Actions actions;

    public CardBureausController(CrudEngine crud, Actions actions) {
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<CardBureau> cardBureausCreate(String idempotencyKey, CardBureauCreate body) {
        ProgramService.validateBureau(actions.tree(body));
        return crud.create("card_bureau", CardBureau.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<CardBureau> cardBureausUpdate(UUID id, String ifMatch, CardBureauUpdate body) {
        ProgramService.validateBureau(actions.tree(body));
        return crud.update("card_bureau", CardBureau.class, body, ifMatch, Crud.params().with("id", id));
    }
}
