package io.meridian.fraud.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.fraud.api.generated.FraudRulesApi;
import io.meridian.fraud.api.generated.model.FraudRule;
import io.meridian.fraud.api.generated.model.FraudRuleCreate;
import io.meridian.fraud.api.generated.model.FraudRuleHitPage;
import io.meridian.fraud.api.generated.model.FraudRuleUpdate;
import io.meridian.fraud.api.generated.model.FraudRulesActivateRequest;
import io.meridian.fraud.api.generated.model.FraudRulesBacktestRequest;
import io.meridian.fraud.api.generated.model.FraudRulesDeactivateRequest;
import io.meridian.fraud.api.generated.model.FraudRulesTestExpressionRequest;
import io.meridian.fraud.application.FraudConfigCache;
import io.meridian.fraud.application.FraudRuleService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Rule authoring, maker-checker activation, testing and backtesting. */
@RestController
public class FraudRulesController implements FraudRulesApi {
    private final FraudRuleService rules;
    private final FraudConfigCache cache;
    private final CrudEngine crud;
    private final Http http;

    public FraudRulesController(FraudRuleService rules, FraudConfigCache cache, CrudEngine crud, Http http) {
        this.rules = rules;
        this.cache = cache;
        this.crud = crud;
        this.http = http;
    }

    @Override
    public ResponseEntity<FraudRule> fraudRulesCreate(String idempotencyKey, FraudRuleCreate body) {
        TenantContext.require();
        rules.validateCreate(http.tree(body));
        ResponseEntity<FraudRule> r = crud.create("fraud_rule", FraudRule.class, body, idempotencyKey, Crud.params());
        cache.invalidate();
        return r;
    }

    @Override
    public ResponseEntity<FraudRule> fraudRulesUpdate(UUID id, String ifMatch, FraudRuleUpdate body) {
        TenantContext.require();
        return http.ok(rules.update(id, http.tree(body), Http.ifMatch(ifMatch)), FraudRule.class);
    }

    @Override
    public ResponseEntity<FraudRule> fraudRulesActivate(UUID id, String idempotencyKey, FraudRulesActivateRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, FraudRule.class,
                () -> http.ok(rules.activate(id, Http.text(req, "mode")), FraudRule.class));
    }

    @Override
    public ResponseEntity<FraudRule> fraudRulesDeactivate(UUID id, String idempotencyKey, FraudRulesDeactivateRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, FraudRule.class,
                () -> http.ok(rules.deactivate(id, Http.text(req, "reason")), FraudRule.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> fraudRulesBacktest(UUID id, String idempotencyKey, FraudRulesBacktestRequest body) {
        TenantContext.require();
        JsonNode req = http.tree(body);
        Integer max = req.hasNonNull("max_events") ? req.get("max_events").asInt() : null;
        return ResponseEntity.ok(rules.backtest(id, OffsetDateTime.parse(req.get("from_ts").asString()),
                OffsetDateTime.parse(req.get("to_ts").asString()), max));
    }

    @Override
    public ResponseEntity<Map<String, Object>> fraudRulesTestExpression(String idempotencyKey, FraudRulesTestExpressionRequest body) {
        TenantContext.require();
        JsonNode req = http.tree(body);
        return ResponseEntity.ok(rules.testExpression(Http.text(req, "expression"), req.get("sample_event")));
    }

    @Override
    public ResponseEntity<FraudRuleHitPage> fraudRulesHits(UUID id, Integer limit, String cursor) {
        crud.get("fraud_rule", Object.class, Crud.params().with("id", id));
        return crud.list("fraud_rule_hit", FraudRuleHitPage.class,
                Crud.params().with("fraud_rule_id", id).with("limit", limit).with("cursor", cursor));
    }
}
