package io.meridian.cardauthorization.api;

import io.meridian.cardauthorization.api.generated.CardAuthorizationRulesApi;
import io.meridian.cardauthorization.api.generated.model.CardAuthorizationRule;
import io.meridian.cardauthorization.api.generated.model.CardAuthorizationRuleCreate;
import io.meridian.cardauthorization.api.generated.model.CardAuthorizationRuleUpdate;
import io.meridian.cardauthorization.api.generated.model.CardAuthorizationRulesTestRequest;
import io.meridian.cardauthorization.application.RuleService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Authorisation rules: CRUD with expression validation (cache invalidated on change) and a test bench. */
@RestController
public class CardAuthorizationRulesController implements CardAuthorizationRulesApi {
    private static final String T = "card_authorization_rule";
    private final RuleService rules;
    private final CrudEngine crud;
    private final Actions actions;

    public CardAuthorizationRulesController(RuleService rules, CrudEngine crud, Actions actions) {
        this.rules = rules;
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<CardAuthorizationRule> cardAuthorizationRulesCreate(String idempotencyKey, CardAuthorizationRuleCreate body) {
        TenantContext.require();
        rules.validate(actions.tree(body), null);
        ResponseEntity<CardAuthorizationRule> r = crud.create(T, CardAuthorizationRule.class, body, idempotencyKey, Crud.params());
        rules.invalidate();
        return r;
    }

    @Override
    public ResponseEntity<CardAuthorizationRule> cardAuthorizationRulesUpdate(UUID id, String ifMatch, CardAuthorizationRuleUpdate body) {
        TenantContext.require();
        JsonNode existing = crud.findOne(T, Map.of("id", id)).map(s -> actions.json().readTree(s))
                .orElseThrow(() -> io.meridian.common.error.ApiException.notFound("card authorization rule", id));
        rules.validate(actions.tree(body), existing);
        ResponseEntity<CardAuthorizationRule> r = crud.update(T, CardAuthorizationRule.class, body, ifMatch, Crud.params().with("id", id));
        rules.invalidate();
        return r;
    }

    @Override
    public ResponseEntity<Void> cardAuthorizationRulesDelete(UUID id) {
        ResponseEntity<Void> r = crud.delete(T, Crud.params().with("id", id));
        rules.invalidate();
        return r;
    }

    @Override
    public ResponseEntity<Map<String, Object>> cardAuthorizationRulesTest(String idempotencyKey, CardAuthorizationRulesTestRequest body) {
        JsonNode b = actions.tree(body);
        return actions.objectAction(idempotencyKey, "rules-test", body, () -> rules.test(b.path("sample"),
                b.hasNonNull("card_program_id") ? UUID.fromString(b.get("card_program_id").asString()) : null,
                b.hasNonNull("condition_expression") ? b.get("condition_expression").asString() : null));
    }
}
