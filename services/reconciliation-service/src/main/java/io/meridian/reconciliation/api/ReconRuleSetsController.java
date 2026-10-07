package io.meridian.reconciliation.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.reconciliation.api.generated.ReconRuleSetsApi;
import io.meridian.reconciliation.api.generated.model.ReconRuleSet;
import io.meridian.reconciliation.api.generated.model.ReconRuleSetCreate;
import io.meridian.reconciliation.api.generated.model.ReconRuleSetUpdate;
import io.meridian.reconciliation.api.generated.model.ReconRuleSetsRejectRequest;
import io.meridian.reconciliation.api.generated.model.ReconRuleSetsSimulateRequest;
import io.meridian.reconciliation.application.Fields;
import io.meridian.reconciliation.application.RuleSetService;
import io.meridian.reconciliation.application.RunService;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Versioned matching rule sets: draft, submit, four-eyes approve / reject, dry-run simulation. */
@RestController
public class ReconRuleSetsController implements ReconRuleSetsApi {
    private final RuleSetService ruleSets;
    private final RunService runs;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public ReconRuleSetsController(RuleSetService ruleSets, RunService runs, Idempotency idempotency,
                                   Responses responses, JsonMapper json) {
        this.ruleSets = ruleSets;
        this.runs = runs;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<ReconRuleSet> reconRuleSetsCreate(String idempotencyKey, ReconRuleSetCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, ReconRuleSet.class,
                () -> responses.created(ruleSets.create(body), ReconRuleSet.class));
    }

    @Override
    public ResponseEntity<ReconRuleSet> reconRuleSetsUpdate(UUID id, String ifMatch, ReconRuleSetUpdate body) {
        TenantContext.require();
        return responses.ok(ruleSets.update(id, ifMatch, body), ReconRuleSet.class);
    }

    @Override
    public ResponseEntity<ReconRuleSet> reconRuleSetsSubmit(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, responses.action("submit", id, null), ReconRuleSet.class,
                () -> responses.ok(ruleSets.submit(id), ReconRuleSet.class));
    }

    @Override
    public ResponseEntity<ReconRuleSet> reconRuleSetsApprove(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, responses.action("approve", id, null), ReconRuleSet.class,
                () -> responses.ok(ruleSets.approve(id, null), ReconRuleSet.class));
    }

    @Override
    public ResponseEntity<ReconRuleSet> reconRuleSetsReject(UUID id, String idempotencyKey,
                                                            ReconRuleSetsRejectRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("reject", id, req), ReconRuleSet.class,
                () -> responses.ok(ruleSets.reject(id, Fields.text(req, "reason")), ReconRuleSet.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> reconRuleSetsSimulate(UUID id, String idempotencyKey,
                                                                     ReconRuleSetsSimulateRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return responses.objectAction(idempotency, idempotencyKey, responses.action("simulate", id, req),
                () -> runs.simulate(id, req));
    }
}
