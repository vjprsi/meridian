package io.meridian.insights.api;

import io.meridian.insights.api.generated.CategoryOverrideRulesApi;
import io.meridian.insights.api.generated.model.CategoryOverrideRule;
import io.meridian.insights.api.generated.model.CategoryOverrideRuleCreate;
import io.meridian.insights.api.generated.model.CategoryOverrideRuleUpdate;
import io.meridian.insights.application.Json;
import io.meridian.insights.application.OverrideRuleService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Customer category override rules (validated; applied to past transactions on create when asked). */
@RestController
public class CategoryOverrideRulesController implements CategoryOverrideRulesApi {
    private final OverrideRuleService rules;
    private final Http http;

    public CategoryOverrideRulesController(OverrideRuleService rules, Http http) {
        this.rules = rules;
        this.http = http;
    }

    @Override
    public ResponseEntity<CategoryOverrideRule> categoryOverrideRulesCreate(String idempotencyKey, CategoryOverrideRuleCreate body) {
        return http.created(idempotencyKey, "category_rule.create", body, CategoryOverrideRule.class, () -> rules.create(body));
    }

    @Override
    public ResponseEntity<CategoryOverrideRule> categoryOverrideRulesUpdate(UUID id, String ifMatch, CategoryOverrideRuleUpdate body) {
        int version = Json.parseVersion(ifMatch);
        return http.withEtag(rules.update(id, body, version), CategoryOverrideRule.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> categoryOverrideRulesApply(UUID id, String idempotencyKey) {
        return http.document(idempotencyKey, "category_rule.apply", id, null, () -> rules.apply(id));
    }
}
