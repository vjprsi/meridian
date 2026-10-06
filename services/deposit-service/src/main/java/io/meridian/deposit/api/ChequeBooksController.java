package io.meridian.deposit.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.api.generated.ChequeBooksApi;
import io.meridian.deposit.api.generated.model.ChequeBook;
import io.meridian.deposit.api.generated.model.ChequeBookCreate;
import io.meridian.deposit.api.generated.model.ChequeBooksCancelRequest;
import io.meridian.deposit.api.generated.model.ChequePage;
import io.meridian.deposit.application.ChequeService;
import io.meridian.deposit.infrastructure.Rows;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Cheque books: issue (creates the leaves), cancel, list cheques. */
@RestController
public class ChequeBooksController implements ChequeBooksApi {
    private final ChequeService cheques;
    private final Actions actions;
    private final CrudEngine crud;
    private final Rows rows;

    public ChequeBooksController(ChequeService cheques, Actions actions, CrudEngine crud, Rows rows) {
        this.cheques = cheques;
        this.actions = actions;
        this.crud = crud;
        this.rows = rows;
    }

    @Override
    public ResponseEntity<ChequeBook> chequeBooksCreate(String idempotencyKey, ChequeBookCreate body) {
        return actions.create(idempotencyKey, body, ChequeBook.class, () -> cheques.issueBook(body));
    }

    @Override
    public ResponseEntity<ChequeBook> chequeBooksCancel(UUID id, String idempotencyKey, ChequeBooksCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, ChequeBook.class,
                () -> cheques.cancelBook(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<ChequePage> chequeBooksCheques(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        rows.get("cheque_book", id);
        return crud.list("cheque", ChequePage.class, Crud.params().with("cheque_book_id", id)
                .with("status", Http.query("status")).with("limit", limit).with("cursor", cursor));
    }
}
