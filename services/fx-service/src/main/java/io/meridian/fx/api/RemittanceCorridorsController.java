package io.meridian.fx.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.fx.api.generated.RemittanceCorridorsApi;
import io.meridian.fx.api.generated.model.FxQuote;
import io.meridian.fx.api.generated.model.RemittanceCorridor;
import io.meridian.fx.api.generated.model.RemittanceCorridorCreate;
import io.meridian.fx.api.generated.model.RemittanceCorridorUpdate;
import io.meridian.fx.api.generated.model.RemittanceCorridorsQuoteRequest;
import io.meridian.fx.api.generated.model.RemittanceCorridorsResumeRequest;
import io.meridian.fx.api.generated.model.RemittanceCorridorsSuspendRequest;
import io.meridian.fx.application.CatalogService;
import io.meridian.fx.application.QuoteService;
import io.meridian.fx.infrastructure.Rows;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Remittance corridors: validated CRUD, pricing (fees, FX, partner, ETA), suspend / resume. */
@RestController
public class RemittanceCorridorsController implements RemittanceCorridorsApi {
    static final String TABLE = "remittance_corridor";
    private final CatalogService catalog;
    private final QuoteService quotes;
    private final CrudEngine crud;
    private final Actions actions;

    public RemittanceCorridorsController(CatalogService catalog, QuoteService quotes, CrudEngine crud, Actions actions) {
        this.catalog = catalog;
        this.quotes = quotes;
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<RemittanceCorridor> remittanceCorridorsCreate(String idempotencyKey, RemittanceCorridorCreate body) {
        TenantContext.require();
        catalog.validateCorridor(actions.tree(body), null);
        return crud.create(TABLE, RemittanceCorridor.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<RemittanceCorridor> remittanceCorridorsUpdate(UUID id, String ifMatch, RemittanceCorridorUpdate body) {
        TenantContext.require();
        catalog.validateCorridor(actions.tree(body), id);
        return crud.update(TABLE, RemittanceCorridor.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<FxQuote> remittanceCorridorsQuote(UUID id, String idempotencyKey, RemittanceCorridorsQuoteRequest body) {
        return actions.action(idempotencyKey, id, "quote", body, FxQuote.class,
                () -> quotes.corridorQuote(id, actions.tree(body)));
    }

    @Override
    public ResponseEntity<RemittanceCorridor> remittanceCorridorsSuspend(UUID id, String idempotencyKey,
                                                                         RemittanceCorridorsSuspendRequest body) {
        return actions.action(idempotencyKey, id, "suspend", body, RemittanceCorridor.class,
                () -> catalog.setCorridorStatus(id, "SUSPENDED", Rows.str(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<RemittanceCorridor> remittanceCorridorsResume(UUID id, String idempotencyKey,
                                                                        RemittanceCorridorsResumeRequest body) {
        return actions.action(idempotencyKey, id, "resume", body, RemittanceCorridor.class,
                () -> catalog.setCorridorStatus(id, "ACTIVE", Rows.str(actions.tree(body), "reason")));
    }
}
