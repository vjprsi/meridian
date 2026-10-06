package io.meridian.config.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.config.api.generated.TaxRateRulesApi;
import io.meridian.config.api.generated.model.TaxRateRule;
import io.meridian.config.api.generated.model.TaxRateRuleCreate;
import io.meridian.config.api.generated.model.TaxRateRuleUpdate;
import io.meridian.config.api.generated.model.TaxRateRulesCalculateRequest;
import io.meridian.config.application.TaxService;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Tax rules (validated, published as platform.tax_rate_rule.changed) and tax calculation. */
@RestController
public class TaxRateRulesController implements TaxRateRulesApi {
    private final TaxService tax;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public TaxRateRulesController(TaxService tax, Idempotency idempotency, JsonMapper json) {
        this.tax = tax;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<TaxRateRule> taxRateRulesCreate(String idempotencyKey, TaxRateRuleCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, TaxRateRule.class,
                () -> Responses.row(json, HttpStatus.CREATED, tax.create(body), TaxRateRule.class));
    }

    @Override
    public ResponseEntity<TaxRateRule> taxRateRulesUpdate(UUID id, String ifMatch, TaxRateRuleUpdate body) {
        TenantContext.require();
        return Responses.row(json, HttpStatus.OK, tax.update(id, body, ifMatch), TaxRateRule.class);
    }

    @Override
    public ResponseEntity<Void> taxRateRulesDelete(UUID id) {
        TenantContext.require();
        tax.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Map<String, Object>> taxRateRulesCalculate(String idempotencyKey,
                                                                     TaxRateRulesCalculateRequest body) {
        TenantContext.require();
        return Responses.object(json, idempotency.execute(idempotencyKey, Responses.request("calculate", null, body),
                JsonNode.class, () -> ResponseEntity.ok(tax.calculate(body.getCountry(), body.getAppliesTo(),
                        body.getAmount(), body.getCurrency(), body.getCustomerSegment(), body.getLegalEntityId()))));
    }
}
