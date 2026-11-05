package io.meridian.casemanagement.api;

import io.meridian.casemanagement.application.CorrectionService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * WE-OPS-1 maker-checker wallet transaction correction. Hand-written {@code @RestController} (the generated
 * {@code WalletTxnCorrectionsApi} will not exist until the host regenerates the contract); its shapes match the
 * {@code wallet-txn-corrections} resource in risk.yaml and it can implement that generated API post-regen. Create
 * is the maker request; approve (a different user) executes the wallet reversal; generated CRUD list / get is
 * served by the generated controller once regenerated.
 */
@RestController
@RequestMapping("/v1/risk/wallet-corrections")
public class WalletTxnCorrectionsController {
    private final CorrectionService corrections;
    private final Http http;

    public WalletTxnCorrectionsController(CorrectionService corrections, Http http) {
        this.corrections = corrections;
        this.http = http;
    }

    @PostMapping
    public ResponseEntity<JsonNode> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestBody JsonNode body) {
        return http.once(key, body, JsonNode.class,
                () -> http.respond(HttpStatus.CREATED, corrections.request(body), JsonNode.class));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<JsonNode> approve(@PathVariable UUID id,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                            @RequestBody(required = false) JsonNode body) {
        return http.once(key, body, JsonNode.class,
                () -> http.ok(corrections.approve(id, Http.text(body, "comment")), JsonNode.class));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<JsonNode> reject(@PathVariable UUID id,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestBody JsonNode body) {
        return http.once(key, body, JsonNode.class,
                () -> http.ok(corrections.reject(id, Http.text(body, "reason")), JsonNode.class));
    }
}
