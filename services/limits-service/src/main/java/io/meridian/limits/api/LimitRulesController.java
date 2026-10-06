package io.meridian.limits.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.limits.api.generated.LimitRulesApi;
import io.meridian.limits.api.generated.model.LimitRule;
import io.meridian.limits.api.generated.model.LimitRuleCreate;
import io.meridian.limits.api.generated.model.LimitRuleUpdate;
import io.meridian.limits.api.generated.model.LimitRulesActivateRequest;
import io.meridian.limits.api.generated.model.LimitRulesDeactivateRequest;
import io.meridian.limits.api.generated.model.LimitRulesSimulateRequest;
import io.meridian.limits.application.LimitAdminService;
import io.meridian.limits.application.LimitCheckService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Limit definitions with maker-checker activation and simulation. */
@RestController
public class LimitRulesController implements LimitRulesApi {
    private final LimitAdminService admin;
    private final LimitCheckService checks;
    private final CrudEngine crud;
    private final Http http;

    public LimitRulesController(LimitAdminService admin, LimitCheckService checks, CrudEngine crud, Http http) {
        this.admin = admin;
        this.checks = checks;
        this.crud = crud;
        this.http = http;
    }

    @Override
    public ResponseEntity<LimitRule> limitRulesCreate(String idempotencyKey, LimitRuleCreate body) {
        TenantContext.require();
        admin.validateRule(http.tree(body));
        return crud.create("limit_rule", LimitRule.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<LimitRule> limitRulesUpdate(UUID id, String ifMatch, LimitRuleUpdate body) {
        TenantContext.require();
        return http.ok(admin.updateRule(id, http.tree(body), Http.ifMatch(ifMatch)), LimitRule.class);
    }

    @Override
    public ResponseEntity<LimitRule> limitRulesActivate(UUID id, String idempotencyKey, LimitRulesActivateRequest body) {
        return http.once(idempotencyKey, body, LimitRule.class, () -> http.ok(admin.activate(id), LimitRule.class));
    }

    @Override
    public ResponseEntity<LimitRule> limitRulesDeactivate(UUID id, String idempotencyKey, LimitRulesDeactivateRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, LimitRule.class, () -> http.ok(admin.deactivate(id, reason), LimitRule.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> limitRulesSimulate(String idempotencyKey, LimitRulesSimulateRequest body) {
        TenantContext.require();
        return ResponseEntity.ok(checks.simulate(http.tree(body)));
    }
}
