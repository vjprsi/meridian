package io.meridian.wallet.api;

import io.meridian.wallet.application.CashoutAuthorisationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * WE-TXN-2 (G-07): agent passcode cash-out (MQ), cardless ATM OTP cash-out with auto-rollback, and cardless /
 * pre-authorised authorisation codes - issuance, redeem, and the expiry / rollback sweep.
 *
 * <p>Unlike the other wallet controllers this one is written against Spring MVC directly rather than implementing a
 * generated {@code *Api} interface, so the wallet-service module compiles in this worktree before the host regenerates
 * the contract from the updated {@code spec/domains/wallets.yaml}. The request/response shapes match the spec actions
 * exactly (bodies are JSON objects; every response is the {@code object} / authorisation row). After the host runs the
 * generator this can be switched to implement {@code WalletCashoutAuthorisationsApi} with no behaviour change.
 */
@RestController
@RequestMapping("/v1/wallet-cashout-authorisations")
public class WalletCashoutAuthorisationsController {
    private final CashoutAuthorisationService authorisations;
    private final Actions actions;

    public WalletCashoutAuthorisationsController(CashoutAuthorisationService authorisations, Actions actions) {
        this.authorisations = authorisations;
        this.actions = actions;
    }

    @PostMapping("/agent-passcode")
    public ResponseEntity<Map<String, Object>> agentPasscode(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                             @RequestBody Map<String, Object> body) {
        return actions.objectAction(idempotencyKey, "cashout-auth:agent-passcode", body,
                () -> authorisations.issue("AGENT_PASSCODE", actions.tree(body), idempotencyKey));
    }

    @PostMapping("/atm-otp")
    public ResponseEntity<Map<String, Object>> atmOtp(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                      @RequestBody Map<String, Object> body) {
        return actions.objectAction(idempotencyKey, "cashout-auth:atm-otp", body,
                () -> authorisations.issue("ATM_OTP", actions.tree(body), idempotencyKey));
    }

    @PostMapping("/auth-code")
    public ResponseEntity<Map<String, Object>> authCode(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                        @RequestBody Map<String, Object> body) {
        return actions.objectAction(idempotencyKey, "cashout-auth:auth-code", body,
                () -> authorisations.issue("AUTH_CODE", actions.tree(body), idempotencyKey));
    }

    @PostMapping("/{id}/redeem")
    public ResponseEntity<Map<String, Object>> redeem(@PathVariable UUID id,
                                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                      @RequestBody Map<String, Object> body) {
        JsonNode req = actions.tree(body);
        return actions.objectAction(idempotencyKey, "cashout-auth:redeem:" + id, body,
                () -> authorisations.redeem(id, Http.text(req, "code"), req));
    }

    @PostMapping("/expire-rollback")
    public ResponseEntity<Map<String, Object>> expireRollback(@RequestHeader("Idempotency-Key") String idempotencyKey) {
        return actions.objectAction(idempotencyKey, "cashout-auth:expire-rollback", null,
                authorisations::expireAndRollback);
    }
}
