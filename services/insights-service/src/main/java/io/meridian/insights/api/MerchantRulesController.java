package io.meridian.insights.api;

import io.meridian.insights.api.generated.MerchantRulesApi;
import io.meridian.insights.api.generated.model.MerchantRule;
import io.meridian.insights.api.generated.model.MerchantRuleCreate;
import io.meridian.insights.api.generated.model.MerchantRuleUpdate;
import io.meridian.insights.api.generated.model.MerchantRulesTestRequest;
import io.meridian.insights.application.Json;
import io.meridian.insights.application.TaxonomyService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Merchant clean-name rules and the rule tester. */
@RestController
public class MerchantRulesController implements MerchantRulesApi {
    private final TaxonomyService taxonomy;
    private final Http http;

    public MerchantRulesController(TaxonomyService taxonomy, Http http) {
        this.taxonomy = taxonomy;
        this.http = http;
    }

    @Override
    public ResponseEntity<MerchantRule> merchantRulesCreate(String idempotencyKey, MerchantRuleCreate body) {
        return http.created(idempotencyKey, "merchant_rule.create", body, MerchantRule.class, () -> taxonomy.createMerchantRule(body));
    }

    @Override
    public ResponseEntity<MerchantRule> merchantRulesUpdate(UUID id, String ifMatch, MerchantRuleUpdate body) {
        int version = Json.parseVersion(ifMatch);
        return http.withEtag(taxonomy.updateMerchantRule(id, body, version), MerchantRule.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> merchantRulesTest(String idempotencyKey, MerchantRulesTestRequest body) {
        return http.document(idempotencyKey, "merchant_rule.test", null, body, () -> taxonomy.test(http.tree(body)));
    }
}
