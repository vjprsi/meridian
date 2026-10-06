package io.meridian.reconciliation.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.reconciliation.api.generated.ReconRulesApi;
import io.meridian.reconciliation.api.generated.model.ReconRule;
import io.meridian.reconciliation.api.generated.model.ReconRuleCreate;
import io.meridian.reconciliation.api.generated.model.ReconRuleUpdate;
import io.meridian.reconciliation.application.RuleService;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Matching rules, validated so that every stored rule is executable. */
@RestController
public class ReconRulesController implements ReconRulesApi {
    private final RuleService rules;
    private final Idempotency idempotency;
    private final Responses responses;

    public ReconRulesController(RuleService rules, Idempotency idempotency, Responses responses) {
        this.rules = rules;
        this.idempotency = idempotency;
        this.responses = responses;
    }

    @Override
    public ResponseEntity<ReconRule> reconRulesCreate(String idempotencyKey, ReconRuleCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, ReconRule.class,
                () -> responses.created(rules.create(body), ReconRule.class));
    }

    @Override
    public ResponseEntity<ReconRule> reconRulesUpdate(UUID id, String ifMatch, ReconRuleUpdate body) {
        TenantContext.require();
        return responses.ok(rules.update(id, ifMatch, body), ReconRule.class);
    }
}
