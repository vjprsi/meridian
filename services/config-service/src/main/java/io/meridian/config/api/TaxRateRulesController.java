package io.meridian.config.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.config.api.generated.TaxRateRulesApi;
import io.meridian.config.api.generated.model.TaxRateRule;
import io.meridian.config.api.generated.model.TaxRateRuleCreate;
import io.meridian.config.api.generated.model.TaxRateRuleUpdate;
import io.meridian.config.api.generated.model.TaxRateRulesCalculateRequest;
import io.meridian.config.application.TaxService;
import io.meridian.web.idempotency.Idempotency;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Tax rules (validated, published as platform.tax_rule.changed) and tax calculation. */
@RestController
public class TaxRateRulesController implements TaxRateRulesApi {
    private final TaxService tax;
    private final Idempotency idempotency;
    private final JsonMapper json;
    private final HttpServletRequest request;

    public TaxRateRulesController(TaxService tax, Idempotency idempotency, JsonMapper json, HttpServletRequest request) {
        this.tax = tax;
        this.idempotency = idempotency;
        this.json = json;
        this.request = request;
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

    /** Contract defect: no response schema is declared, so the generated method returns ResponseEntity&lt;Void&gt;. */
    @Override
    public ResponseEntity<Void> taxRateRulesCalculate(String idempotencyKey, TaxRateRulesCalculateRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        BigDecimal amount;
        try {
            amount = new BigDecimal(req.path("amount").asString(""));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_value", "amount must be a decimal string");
        }
        // Contract gap: the request has no legal_entity_id; it is accepted as ?legal_entity_id= so that
        // legal-entity-specific rules (e.g. seeded from country packs) can apply.
        UUID legalEntityId = null;
        String le = request.getParameter("legal_entity_id");
        if (le != null && !le.isBlank()) {
            try {
                legalEntityId = UUID.fromString(le.trim());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("invalid_value", "legal_entity_id must be a UUID");
            }
        }
        UUID entity = legalEntityId;
        return Responses.body(idempotency.execute(idempotencyKey, Responses.request("calculate", entity, body), JsonNode.class,
                () -> ResponseEntity.ok(tax.calculate(req.path("country").asString(), req.path("applies_to").asString(),
                        amount, req.path("currency").asString(null), req.path("customer_segment").asString(null), entity))));
    }
}
