package io.meridian.cardtokenization.api;

import io.meridian.cardtokenization.api.generated.CardTokensApi;
import io.meridian.cardtokenization.api.generated.model.CardToken;
import io.meridian.cardtokenization.api.generated.model.CardTokenEventPage;
import io.meridian.cardtokenization.api.generated.model.CardTokensCardStatusSyncRequest;
import io.meridian.cardtokenization.api.generated.model.CardTokensDeactivateRequest;
import io.meridian.cardtokenization.api.generated.model.CardTokensNetworkEventsRequest;
import io.meridian.cardtokenization.api.generated.model.CardTokensNetworkProvisionRequestsRequest;
import io.meridian.cardtokenization.api.generated.model.CardTokensPushProvisionRequest;
import io.meridian.cardtokenization.api.generated.model.CardTokensSuspendRequest;
import io.meridian.cardtokenization.api.generated.model.CardTokensVerifyStepUpRequest;
import io.meridian.cardtokenization.application.TokenService;
import io.meridian.cardtokenization.config.CardsProperties;
import io.meridian.cardtokenization.config.TokenizationConfig;
import io.meridian.cardtokenization.domain.Crypto;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Network tokens: issuer push provisioning and lifecycle actions, plus the public, HMAC-signed token-service
 * callbacks (provisioning requests and lifecycle notifications) bound to the tenant of the callback URL.
 * Contract: generated/openapi/services/card-tokenization-service.openapi.yaml
 */
@RestController
public class CardTokensController implements CardTokensApi {
    private final io.meridian.web.callback.CallbackVerifier callbacks;
    static final String SIGNATURE_HEADER = "X-Signature";

    private final TokenService tokens;
    private final Actions actions;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final CardsProperties props;
    private final HttpServletRequest request;

    CardTokensController(TokenService tokens, Actions actions, CrudEngine crud, Idempotency idempotency,
                         CardsProperties props, HttpServletRequest request,
                         io.meridian.web.callback.CallbackVerifier callbacks) {
        this.callbacks = callbacks;
        this.tokens = tokens;
        this.actions = actions;
        this.crud = crud;
        this.idempotency = idempotency;
        this.props = props;
        this.request = request;
    }

    @Override
    public ResponseEntity<CardToken> cardTokensPushProvision(String idempotencyKey, CardTokensPushProvisionRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, null, "push-provision", body, CardToken.class, () -> tokens.pushProvision(
                UUID.fromString(req.get("card_id").asString()), req.get("token_requestor").asString(),
                req.path("token_requestor_id").asString(null), req.get("device_info")));
    }

    @Override
    public ResponseEntity<CardToken> cardTokensNetworkProvisionRequests(UUID tenantId,
                                                                        CardTokensNetworkProvisionRequestsRequest body) {
        verify(tenantId, TokenizationConfig.currentBody());
        JsonNode req = actions.tree(body);
        ObjectNode row = Actions.asTenant(tenantId, "token-service-callback", () -> tokens.networkProvision(req));
        return ResponseEntity.ok(actions.model(row, CardToken.class));
    }

    @Override
    public ResponseEntity<CardToken> cardTokensNetworkEvents(UUID tenantId, CardTokensNetworkEventsRequest body) {
        verify(tenantId, TokenizationConfig.currentBody());
        JsonNode req = actions.tree(body);
        ObjectNode row = Actions.asTenant(tenantId, "token-service-callback", () -> tokens.networkEvent(
                req.get("network_token_ref").asString(), req.get("event_type").asString(),
                req.path("reason").asString(null)));
        return ResponseEntity.ok(actions.model(row, CardToken.class));
    }

    @Override
    public ResponseEntity<CardToken> cardTokensVerifyStepUp(UUID id, String idempotencyKey,
                                                            CardTokensVerifyStepUpRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "verify-step-up", body, CardToken.class,
                () -> tokens.verifyStepUp(id, req.path("otp").asString(null)));
    }

    @Override
    public ResponseEntity<CardToken> cardTokensSuspend(UUID id, String idempotencyKey, CardTokensSuspendRequest body) {
        String reason = actions.tree(body).path("reason").asString(null);
        return actions.action(idempotencyKey, id, "suspend", body, CardToken.class,
                () -> tokens.issuerTransition(id, "SUSPENDED", reason == null ? "SUSPENDED_BY_ISSUER" : reason));
    }

    @Override
    public ResponseEntity<CardToken> cardTokensResume(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "resume", null, CardToken.class,
                () -> tokens.issuerTransition(id, "ACTIVE", "RESUMED_BY_ISSUER"));
    }

    @Override
    public ResponseEntity<CardToken> cardTokensDeactivate(UUID id, String idempotencyKey, CardTokensDeactivateRequest body) {
        String reason = actions.tree(body).path("reason").asString(null);
        return actions.action(idempotencyKey, id, "deactivate", body, CardToken.class,
                () -> tokens.issuerTransition(id, "DEACTIVATED", reason == null ? "DEACTIVATED_BY_ISSUER" : reason));
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ResponseEntity<Map<String, Object>> cardTokensCardStatusSync(String idempotencyKey,
                                                                        CardTokensCardStatusSyncRequest body) {
        TenantContext.require();
        JsonNode req = actions.tree(body);
        UUID cardId = UUID.fromString(req.get("card_id").asString());
        String status = req.get("card_status").asString();
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("action", "card-status-sync");
        fingerprint.put("body", req);
        ResponseEntity raw = idempotency.execute(idempotencyKey, fingerprint, Map.class, () -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("card_id", cardId.toString());
            result.put("card_status", status);
            result.put("updated", tokens.cardStatusSync(cardId, status));
            return ResponseEntity.ok(result);
        });
        return (ResponseEntity<Map<String, Object>>) raw;
    }

    @Override
    public ResponseEntity<CardTokenEventPage> cardTokensEvents(UUID id, Integer limit, String cursor) {
        crud.get("card_token", CardToken.class, Crud.params().with("id", id));
        return crud.list("card_token_event", CardTokenEventPage.class,
                Crud.params().with("card_token_id", id).with("limit", limit).with("cursor", cursor));
    }

    /** SEC-17: v2 signature (timestamp, nonce, ?tenant_id bound), legacy body-only while the deprecation flag is on. */
    private void verify(UUID tenantId, byte[] body) {
        String secret = props.callbackSecret();
        if (secret == null || secret.isBlank() || tenantId == null) {
            throw Actions.invalidSignature();
        }
        callbacks.verify("token-service", tenantId.toString(), body, request.getHeader(SIGNATURE_HEADER),
                io.meridian.web.callback.CallbackVerifier.Headers.of(request), b -> Crypto.hmacHex(secret, b));
    }
}
