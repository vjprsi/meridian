package io.meridian.routing.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.routing.api.generated.RoutingRulesApi;
import io.meridian.routing.api.generated.model.PaymentRoutingRule;
import io.meridian.routing.api.generated.model.PaymentRoutingRuleCreate;
import io.meridian.routing.api.generated.model.PaymentRoutingRuleUpdate;
import io.meridian.routing.api.generated.model.RoutingRulesRouteRequest;
import io.meridian.routing.api.generated.model.RoutingRulesSimulateRequest;
import io.meridian.routing.application.RoutingService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Routing rules: validated CRUD, simulate (explained) and route (decision for payment-service). */
@RestController
public class RoutingRulesController implements RoutingRulesApi {
    private static final String TABLE = "payment_routing_rule";
    private final RoutingService routing;
    private final Actions actions;
    private final CrudEngine crud;

    public RoutingRulesController(RoutingService routing, Actions actions, CrudEngine crud) {
        this.routing = routing;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<PaymentRoutingRule> routingRulesCreate(String idempotencyKey, PaymentRoutingRuleCreate body) {
        TenantContext.require();
        routing.validate(actions.tree(body));
        return crud.create(TABLE, PaymentRoutingRule.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<PaymentRoutingRule> routingRulesUpdate(UUID id, String ifMatch, PaymentRoutingRuleUpdate body) {
        TenantContext.require();
        routing.validate(actions.tree(body));
        return crud.update(TABLE, PaymentRoutingRule.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> routingRulesSimulate(String idempotencyKey, RoutingRulesSimulateRequest body) {
        return actions.object(idempotencyKey, null, "simulate", body,
                () -> routing.simulate(actions.tree(body).get("payment")));
    }

    @Override
    public ResponseEntity<Map<String, Object>> routingRulesRoute(String idempotencyKey, RoutingRulesRouteRequest body) {
        return actions.object(idempotencyKey, null, "route", body, () -> routing.route(actions.tree(body).get("payment")));
    }
}
