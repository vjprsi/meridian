package io.meridian.productcatalog.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.productcatalog.api.generated.FeeDefinitionsApi;
import io.meridian.productcatalog.api.generated.model.FeeDefinition;
import io.meridian.productcatalog.api.generated.model.FeeDefinitionCreate;
import io.meridian.productcatalog.api.generated.model.FeeDefinitionUpdate;
import io.meridian.productcatalog.api.generated.model.FeeDefinitionsCalculateRequest;
import io.meridian.productcatalog.api.generated.model.FeeTierPage;
import io.meridian.productcatalog.api.generated.model.FeeWaiverRulePage;
import io.meridian.productcatalog.application.PricingService;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fee definitions (guarded content), their tiers and waiver rules, and the fee dry-run calculation. */
@RestController
public class FeeDefinitionsController implements FeeDefinitionsApi {
    private final ContentEndpoints content;
    private final PricingService pricing;
    private final CatalogStore store;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public FeeDefinitionsController(ContentEndpoints content, PricingService pricing, CatalogStore store, CrudEngine crud,
                                    Idempotency idempotency, JsonMapper json) {
        this.content = content;
        this.pricing = pricing;
        this.store = store;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<FeeDefinition> feeDefinitionsCreate(String idempotencyKey, FeeDefinitionCreate body) {
        return content.create("fee_definition", FeeDefinition.class, idempotencyKey, body);
    }

    @Override
    public ResponseEntity<FeeDefinition> feeDefinitionsUpdate(UUID id, String ifMatch, FeeDefinitionUpdate body) {
        return content.update("fee_definition", FeeDefinition.class, id, ifMatch, body);
    }

    @Override
    public ResponseEntity<Void> feeDefinitionsDelete(UUID id) {
        return content.delete("fee_definition", id);
    }

    @Override
    public ResponseEntity<FeeTierPage> feeDefinitionsTiers(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        store.require("fee_definition", id);
        return crud.list("fee_tier", FeeTierPage.class,
                Crud.params().with("fee_definition_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<FeeWaiverRulePage> feeDefinitionsWaiverRules(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        store.require("fee_definition", id);
        return crud.list("fee_waiver_rule", FeeWaiverRulePage.class,
                Crud.params().with("fee_definition_id", id).with("limit", limit).with("cursor", cursor));
    }

    /**
     * Dry run. Besides base_amount / occurrence_no / customer_id, an optional {@code context} object in the body
     * (balance, average_balance, salary_credit, transaction_count, segment, bundle_member, promotion_code,
     * manual_waiver, charged_in_period, as_of) feeds the waiver rules and period cap.
     */
    @Override
    public ResponseEntity<Void> feeDefinitionsCalculate(UUID id, String idempotencyKey, FeeDefinitionsCalculateRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        BigDecimal base = new BigDecimal(req.get("base_amount").asString());
        Integer occurrence = req.hasNonNull("occurrence_no") ? req.get("occurrence_no").asInt() : null;
        UUID customer = req.hasNonNull("customer_id") ? UUID.fromString(req.get("customer_id").asString()) : null;
        Map<String, Object> extras = ApiSupport.rawBodyExtras(json);
        Map<String, Object> context = new LinkedHashMap<>(ApiSupport.queryParams());
        if (extras.get("context") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> context.put(String.valueOf(k), v));
        }
        return ApiSupport.asVoid(idempotency.execute(idempotencyKey, ApiSupport.idem(json, "fee-calculate", id, body),
                String.class, () -> ResponseEntity.ok(store.toNode(
                        pricing.calculateFee(id, base, occurrence, customer, context)).toString())));
    }
}
