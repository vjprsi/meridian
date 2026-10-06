package io.meridian.fx.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.fx.api.generated.FxMarginRulesApi;
import io.meridian.fx.api.generated.model.FxMarginRule;
import io.meridian.fx.api.generated.model.FxMarginRuleCreate;
import io.meridian.fx.api.generated.model.FxMarginRuleUpdate;
import io.meridian.fx.application.CatalogService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** FX margin rules: generic CRUD with pair, band, margin and validity validation. */
@RestController
public class FxMarginRulesController implements FxMarginRulesApi {
    static final String TABLE = "fx_margin_rule";
    private final CatalogService catalog;
    private final CrudEngine crud;
    private final Actions actions;

    public FxMarginRulesController(CatalogService catalog, CrudEngine crud, Actions actions) {
        this.catalog = catalog;
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<FxMarginRule> fxMarginRulesCreate(String idempotencyKey, FxMarginRuleCreate body) {
        TenantContext.require();
        catalog.validateMarginRule(actions.tree(body), true);
        return crud.create(TABLE, FxMarginRule.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<FxMarginRule> fxMarginRulesUpdate(UUID id, String ifMatch, FxMarginRuleUpdate body) {
        TenantContext.require();
        catalog.validateMarginRule(actions.tree(body), false);
        return crud.update(TABLE, FxMarginRule.class, body, ifMatch, Crud.params().with("id", id));
    }
}
