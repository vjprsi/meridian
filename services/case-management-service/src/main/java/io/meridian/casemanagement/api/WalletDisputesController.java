package io.meridian.casemanagement.api;

import io.meridian.casemanagement.application.DisputeService;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * WE-OPS-1 wallet dispute tracker. Hand-written {@code @RestController} (the generated {@code WalletDisputesApi} will
 * not exist until the host regenerates the contract from the spec); its shapes match the {@code wallet-disputes}
 * resource in risk.yaml and it can implement that generated API post-regen. Generated CRUD (list / get / update)
 * is served by the generated controller once regenerated; create and the lifecycle actions are provided here.
 */
@RestController
@RequestMapping("/v1/risk/wallet-disputes")
public class WalletDisputesController {
    private final DisputeService disputes;
    private final Http http;
    private final JsonMapper json;

    public WalletDisputesController(DisputeService disputes, Http http, JsonMapper json) {
        this.disputes = disputes;
        this.http = http;
        this.json = json;
    }

    @PostMapping
    public ResponseEntity<JsonNode> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestBody JsonNode body) {
        return http.once(key, body, JsonNode.class,
                () -> http.respond(HttpStatus.CREATED, disputes.raise(body), JsonNode.class));
    }

    @PostMapping("/{id}/investigate")
    public ResponseEntity<JsonNode> investigate(@PathVariable UUID id,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                @RequestBody(required = false) JsonNode body) {
        return http.once(key, body, JsonNode.class,
                () -> http.ok(disputes.investigate(id, Http.text(body, "note")), JsonNode.class));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<JsonNode> resolve(@PathVariable UUID id,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                            @RequestBody JsonNode body) {
        return http.once(key, body, JsonNode.class, () -> http.ok(disputes.resolve(id,
                Http.text(body, "resolution_type"), Http.text(body, "resolution_notes"),
                Http.text(body, "correction_reason"), dec(body, "correction_amount"),
                Http.text(body, "correction_rationale")), JsonNode.class));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<JsonNode> reject(@PathVariable UUID id,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestBody JsonNode body) {
        return http.once(key, body, JsonNode.class,
                () -> http.ok(disputes.reject(id, Http.text(body, "reason")), JsonNode.class));
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<JsonNode> withdraw(@PathVariable UUID id,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                             @RequestBody JsonNode body) {
        return http.once(key, body, JsonNode.class,
                () -> http.ok(disputes.withdraw(id, Http.text(body, "reason")), JsonNode.class));
    }

    @GetMapping("/{id}/evidence")
    public ResponseEntity<JsonNode> listEvidence(@PathVariable UUID id) {
        return ResponseEntity.ok(json.readTree(disputes.evidence(id)));
    }

    @PostMapping("/{id}/evidence")
    public ResponseEntity<JsonNode> addEvidence(@PathVariable UUID id,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                @RequestBody JsonNode body) {
        return http.once(key, body, JsonNode.class,
                () -> http.respond(HttpStatus.CREATED, disputes.addEvidence(id, body), JsonNode.class));
    }

    private static BigDecimal dec(JsonNode n, String f) {
        String s = Http.text(n, f);
        return s == null || s.isBlank() ? null : new BigDecimal(s);
    }
}
