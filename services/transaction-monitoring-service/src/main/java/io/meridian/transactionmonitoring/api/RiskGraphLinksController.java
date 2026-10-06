package io.meridian.transactionmonitoring.api;

import io.meridian.transactionmonitoring.api.generated.RiskGraphLinksApi;
import io.meridian.transactionmonitoring.api.generated.model.RiskGraphLink;
import io.meridian.transactionmonitoring.api.generated.model.RiskGraphLinksObserveRequest;
import io.meridian.transactionmonitoring.application.GraphService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Observed links between graph nodes (shared device / phone / address, transfers). */
@RestController
public class RiskGraphLinksController implements RiskGraphLinksApi {
    private final GraphService graph;
    private final Http http;

    public RiskGraphLinksController(GraphService graph, Http http) {
        this.graph = graph;
        this.http = http;
    }

    @Override
    public ResponseEntity<RiskGraphLink> riskGraphLinksObserve(String idempotencyKey, RiskGraphLinksObserveRequest body) {
        return http.once(idempotencyKey, body, RiskGraphLink.class, () -> http.ok(graph.observe(http.tree(body)), RiskGraphLink.class));
    }
}
