package io.meridian.fraud.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.fraud.api.generated.FraudDecisionPoliciesApi;
import io.meridian.fraud.api.generated.model.FraudDecisionPolicy;
import io.meridian.fraud.api.generated.model.FraudDecisionPolicyCreate;
import io.meridian.fraud.api.generated.model.FraudDecisionPolicyUpdate;
import io.meridian.fraud.application.FraudRuleService;
import io.meridian.fraud.infrastructure.Db;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Score thresholds per event type; threshold ordering is validated and the scoring cache refreshed on change. */
@RestController
public class FraudDecisionPoliciesController implements FraudDecisionPoliciesApi {
    private final FraudRuleService rules;
    private final CrudEngine crud;
    private final Db db;
    private final Http http;

    public FraudDecisionPoliciesController(FraudRuleService rules, CrudEngine crud, Db db, Http http) {
        this.rules = rules;
        this.crud = crud;
        this.db = db;
        this.http = http;
    }

    @Override
    public ResponseEntity<FraudDecisionPolicy> fraudDecisionPoliciesCreate(String idempotencyKey, FraudDecisionPolicyCreate body) {
        TenantContext.require();
        rules.validatePolicy(null, http.tree(body));
        ResponseEntity<FraudDecisionPolicy> r = crud.create("fraud_decision_policy", FraudDecisionPolicy.class, body,
                idempotencyKey, Crud.params());
        rules.policyChanged();
        return r;
    }

    @Override
    public ResponseEntity<FraudDecisionPolicy> fraudDecisionPoliciesUpdate(UUID id, String ifMatch, FraudDecisionPolicyUpdate body) {
        TenantContext.require();
        rules.validatePolicy(db.require("fraud_decision_policy", id, false), http.tree(body));
        ResponseEntity<FraudDecisionPolicy> r = crud.update("fraud_decision_policy", FraudDecisionPolicy.class, body,
                ifMatch, Crud.params().with("id", id));
        rules.policyChanged();
        return r;
    }
}
