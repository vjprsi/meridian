package io.meridian.card.api;

import io.meridian.card.api.generated.CardProgramsApi;
import io.meridian.card.api.generated.model.CardPage;
import io.meridian.card.api.generated.model.CardProgram;
import io.meridian.card.api.generated.model.CardProgramCreate;
import io.meridian.card.api.generated.model.CardProgramUpdate;
import io.meridian.card.api.generated.model.CardProgramsActivateRequest;
import io.meridian.card.api.generated.model.CardProgramsSuspendRequest;
import io.meridian.card.application.ProgramService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestController;

/** Card programs: BIN range validation, certification, maker-checker go-live, suspension. */
@RestController
public class CardProgramsController implements CardProgramsApi {
    private final ProgramService programs;
    private final Actions actions;
    private final CrudEngine crud;
    private final TransactionTemplate tx;

    public CardProgramsController(ProgramService programs, Actions actions, CrudEngine crud, TransactionTemplate tx) {
        this.programs = programs;
        this.actions = actions;
        this.crud = crud;
        this.tx = tx;
    }

    @Override
    public ResponseEntity<CardProgram> cardProgramsCreate(String idempotencyKey, CardProgramCreate body) {
        return actions.create(idempotencyKey, body, CardProgram.class, () -> programs.createProgram(actions.tree(body)));
    }

    @Override
    public ResponseEntity<CardProgram> cardProgramsUpdate(UUID id, String ifMatch, CardProgramUpdate body) {
        TenantContext.require();
        return tx.execute(s -> {
            programs.checkProgramUpdate(id, actions.tree(body));
            return crud.update("card_program", CardProgram.class, body, ifMatch, Crud.params().with("id", id));
        });
    }

    @Override
    public ResponseEntity<CardProgram> cardProgramsCertify(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "certify", null, CardProgram.class, () -> programs.certify(id));
    }

    @Override
    public ResponseEntity<CardProgram> cardProgramsActivate(UUID id, String idempotencyKey, CardProgramsActivateRequest body) {
        return actions.action(idempotencyKey, id, "activate", body, CardProgram.class,
                () -> programs.activate(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<CardProgram> cardProgramsSuspend(UUID id, String idempotencyKey, CardProgramsSuspendRequest body) {
        return actions.action(idempotencyKey, id, "suspend", body, CardProgram.class,
                () -> programs.suspend(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<CardPage> cardProgramsCards(UUID id, Integer limit, String cursor) {
        crud.get("card_program", CardProgram.class, Crud.params().with("id", id));
        return crud.list("card", CardPage.class, Crud.params().with("card_program_id", id).with("limit", limit)
                .with("cursor", cursor));
    }
}
