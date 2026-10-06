package io.meridian.insights.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.insights.api.generated.TransactionEnrichmentsApi;
import io.meridian.insights.api.generated.model.TransactionEnrichment;
import io.meridian.insights.api.generated.model.TransactionEnrichmentsEnrichRequest;
import io.meridian.insights.api.generated.model.TransactionEnrichmentsRecategoriseRequest;
import io.meridian.insights.application.EnrichmentService;
import io.meridian.insights.application.Json;
import io.meridian.insights.application.OverrideRuleService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Transaction enrichment (idempotent per source), current enrichment and re-categorisation. */
@RestController
public class TransactionEnrichmentsController implements TransactionEnrichmentsApi {
    private final EnrichmentService enrichments;
    private final OverrideRuleService rules;
    private final Http http;

    public TransactionEnrichmentsController(EnrichmentService enrichments, OverrideRuleService rules, Http http) {
        this.enrichments = enrichments;
        this.rules = rules;
        this.http = http;
    }

    @Override
    public ResponseEntity<TransactionEnrichment> transactionEnrichmentsEnrich(String idempotencyKey,
                                                                              TransactionEnrichmentsEnrichRequest body) {
        return http.ok(idempotencyKey, "enrichment.enrich", null, body, TransactionEnrichment.class,
                () -> enrichments.enrich(http.tree(body)));
    }

    @Override
    public ResponseEntity<TransactionEnrichment> transactionEnrichmentsCurrent(String sourceType, UUID sourceId) {
        TenantContext.require();
        return enrichments.current(sourceType, sourceId)
                .map(r -> ResponseEntity.ok(http.read(r, TransactionEnrichment.class)))
                .orElseThrow(() -> ApiException.notFound("transaction enrichment", sourceType + "/" + sourceId));
    }

    @Override
    public ResponseEntity<TransactionEnrichment> transactionEnrichmentsRecategorise(UUID id, String idempotencyKey,
                                                                                    TransactionEnrichmentsRecategoriseRequest body) {
        JsonNode req = http.tree(body);
        UUID category = Json.uuid(req, "spend_category_id");
        boolean createRule = Json.bool(req, "create_rule");
        return http.ok(idempotencyKey, "enrichment.recategorise", id, body, TransactionEnrichment.class,
                () -> enrichments.recategorise(id, category, createRule, rules));
    }
}
