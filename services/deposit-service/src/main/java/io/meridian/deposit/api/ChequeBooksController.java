package io.meridian.deposit.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.api.generated.ChequeBooksApi;
import io.meridian.deposit.api.generated.model.ChequeBook;
import io.meridian.deposit.api.generated.model.ChequeBookCreate;
import io.meridian.deposit.api.generated.model.ChequeBooksCancelRequest;
import io.meridian.deposit.api.generated.model.ChequeBooksDispatchRequest;
import io.meridian.deposit.api.generated.model.ChequePage;
import io.meridian.deposit.application.ChequeService;
import io.meridian.deposit.infrastructure.Rows;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Cheque books: issue (creates the leaves), dispatch, activate on receipt, cancel, list cheques. */
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
    public ResponseEntity<ChequeBook> chequeBooksDispatch(UUID id, String idempotencyKey, ChequeBooksDispatchRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "dispatch", body, ChequeBook.class,
                () -> cheques.dispatchBook(id, Http.text(req, "dispatch_reference"), Http.uuid(req, "delivery_branch_id")));
    }

    @Override
    public ResponseEntity<ChequeBook> chequeBooksActivate(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "activate", null, ChequeBook.class, () -> cheques.activateBook(id));
    }

    @Override
    public ResponseEntity<ChequeBook> chequeBooksCancel(UUID id, String idempotencyKey, ChequeBooksCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, ChequeBook.class,
                () -> cheques.cancelBook(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<ChequePage> chequeBooksCheques(UUID id, String status, Integer limit, String cursor) {
        TenantContext.require();
        rows.get("cheque_book", id);
        return crud.list("cheque", ChequePage.class, Crud.params().with("cheque_book_id", id)
                .with("status", status).with("limit", limit).with("cursor", cursor));
    }
}
