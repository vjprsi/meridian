package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessApprovalRulesApi;
import io.meridian.businesschannel.api.generated.model.BusinessApprovalRule;
import io.meridian.businesschannel.api.generated.model.BusinessApprovalRulePage;
import io.meridian.businesschannel.api.generated.model.BusinessApprovalRulesCreateRequest;
import io.meridian.businesschannel.api.generated.model.BusinessApprovalRulesReplaceRequest;
import io.meridian.businesschannel.application.ApprovalRuleService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The business's approval matrix (ADMIN). */
@RestController
public class BusinessApprovalRulesController implements BusinessApprovalRulesApi {
    private final ApprovalRuleService rules;
    private final Http http;

    public BusinessApprovalRulesController(ApprovalRuleService rules, Http http) {
        this.rules = rules;
        this.http = http;
    }

    @Override
    public ResponseEntity<BusinessApprovalRulePage> businessApprovalRulesList(Integer limit, String cursor) {
        return http.ok(rules.list(), BusinessApprovalRulePage.class);
    }

    @Override
    public ResponseEntity<BusinessApprovalRule> businessApprovalRulesCreate(String idempotencyKey,
                                                                            BusinessApprovalRulesCreateRequest body) {
        return http.once(idempotencyKey, body, BusinessApprovalRule.class, () -> rules.create(http.body(body)));
    }

    @Override
    public ResponseEntity<BusinessApprovalRule> businessApprovalRulesReplace(UUID ruleId, String idempotencyKey,
                                                                             BusinessApprovalRulesReplaceRequest body) {
        return http.once(idempotencyKey, body, BusinessApprovalRule.class, () -> rules.replace(ruleId, http.body(body)));
    }

    @Override
    public ResponseEntity<Void> businessApprovalRulesDelete(UUID ruleId, String idempotencyKey) {
        rules.delete(ruleId);
        return ResponseEntity.noContent().build();
    }
}
