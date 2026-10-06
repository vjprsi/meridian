package io.meridian.transactionmonitoring.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.transactionmonitoring.api.generated.RiskGraphEntitiesApi;
import io.meridian.transactionmonitoring.api.generated.model.RiskGraphLinkPage;
import io.meridian.transactionmonitoring.application.GraphService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Risk graph nodes: their edges and the surrounding network. */
@RestController
public class RiskGraphEntitiesController implements RiskGraphEntitiesApi {
    private final GraphService graph;
    private final Http http;

    public RiskGraphEntitiesController(GraphService graph, Http http) {
        this.graph = graph;
        this.http = http;
    }

    @Override
    public ResponseEntity<RiskGraphLinkPage> riskGraphEntitiesLinks(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        return http.ok(graph.links(id, limit, cursor), RiskGraphLinkPage.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> riskGraphEntitiesNetwork(UUID id, Integer depth, Integer maxNodes) {
        TenantContext.require();
        return ResponseEntity.ok(graph.network(id, depth, maxNodes));
    }
}
