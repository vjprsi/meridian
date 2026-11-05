package io.meridian.reconciliation.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.reconciliation.application.SafeguardingService;
import io.meridian.web.idempotency.Idempotency;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * WE-RECON-1 (G-10): trust / e-money safeguarding end-of-day stock reconciliation {@code A-(B+C+D+E)} and shortfall
 * alarm. {@code POST /run} assesses every active safeguarding config for a business date (idempotent per tenant,
 * config and business date), applying any component figures supplied on the request; {@code GET /{id}} reads one
 * stored run.
 *
 * <p>Like the WE-TXN-2 / WE-ETM-1 controllers this is written against Spring MVC directly rather than implementing a
 * generated {@code *Api} interface, so reconciliation-service compiles in this worktree before the host regenerates
 * the contract from the updated {@code spec/domains/ledger.yaml}. The request / response shapes match the spec
 * actions; after regeneration this can implement {@code SafeguardingRunsApi} with no behaviour change, and the
 * standard {@code list} / {@code get} are then served by the generated controller.
 */
@RestController
@RequestMapping("/v1/reconciliation/safeguarding-runs")
public class SafeguardingRunsController {
    private final SafeguardingService safeguarding;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public SafeguardingRunsController(SafeguardingService safeguarding, Idempotency idempotency, Responses responses,
                                      JsonMapper json) {
        this.safeguarding = safeguarding;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(
            @RequestParam("business_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("action", "safeguarding-run");
        fingerprint.put("business_date", String.valueOf(businessDate));
        fingerprint.put("body", req);
        return responses.objectAction(idempotency, idempotencyKey, fingerprint,
                () -> safeguarding.run(businessDate, req));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) {
        TenantContext.require();
        return ResponseEntity.ok(safeguarding.get(id));
    }
}
