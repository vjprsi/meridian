package io.meridian.cardthreeds.api;

import io.meridian.cardthreeds.api.generated.ThreedsAuthenticationsApi;
import io.meridian.cardthreeds.api.generated.model.ThreedsAuthentication;
import io.meridian.cardthreeds.api.generated.model.ThreedsAuthenticationsApproveRequest;
import io.meridian.cardthreeds.api.generated.model.ThreedsAuthenticationsAuthenticateRequest;
import io.meridian.cardthreeds.api.generated.model.ThreedsAuthenticationsDeclineRequest;
import io.meridian.cardthreeds.api.generated.model.ThreedsAuthenticationsVerifyOtpRequest;
import io.meridian.cardthreeds.application.ThreedsService;
import io.meridian.cardthreeds.config.CardsProperties;
import io.meridian.cardthreeds.config.ThreedsConfig;
import io.meridian.cardthreeds.domain.Crypto;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.idempotency.Idempotency;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 3-D Secure ACS: the public, HMAC-signed directory-server endpoints (AReq, results request) bound to the tenant of
 * the ACS URL, and the cardholder challenge actions (OTP, app approval, decline). The CAVV is returned only on the
 * directory-server endpoints. Contract: generated/openapi/services/card-threeds-service.openapi.yaml
 */
@RestController
public class ThreedsAuthenticationsController implements ThreedsAuthenticationsApi {
    static final String SIGNATURE_HEADER = "X-Signature";

    private final ThreedsService threeds;
    private final Idempotency idempotency;
    private final JsonMapper json;
    private final CardsProperties props;
    private final HttpServletRequest request;

    ThreedsAuthenticationsController(ThreedsService threeds, Idempotency idempotency, JsonMapper json,
                                     CardsProperties props, HttpServletRequest request) {
        this.threeds = threeds;
        this.idempotency = idempotency;
        this.json = json;
        this.props = props;
        this.request = request;
    }

    @Override
    public ResponseEntity<ThreedsAuthentication> threedsAuthenticationsAuthenticate(
            UUID tenantId, ThreedsAuthenticationsAuthenticateRequest body) {
        verify(ThreedsConfig.currentBody());
        JsonNode req = json.valueToTree(body);
        ObjectNode row = asTenant(tenantId, () -> threeds.authenticate(req));
        return ResponseEntity.ok(json.treeToValue(row, ThreedsAuthentication.class));
    }

    @Override
    public ResponseEntity<ThreedsAuthentication> threedsAuthenticationsResult(UUID id, UUID tenantId,
                                                                              String dsTransactionId) {
        verify(dsTransactionId.getBytes(StandardCharsets.UTF_8));
        ObjectNode row = asTenant(tenantId, () -> threeds.result(id, dsTransactionId));
        return ResponseEntity.ok(json.treeToValue(row, ThreedsAuthentication.class));
    }

    @Override
    public ResponseEntity<ThreedsAuthentication> threedsAuthenticationsVerifyOtp(
            UUID id, String idempotencyKey, ThreedsAuthenticationsVerifyOtpRequest body) {
        String otp = json.valueToTree(body).path("otp").asString(null);
        return action(idempotencyKey, id, "verify-otp", body, () -> threeds.verifyOtp(id, otp));
    }

    @Override
    public ResponseEntity<ThreedsAuthentication> threedsAuthenticationsApprove(
            UUID id, String idempotencyKey, ThreedsAuthenticationsApproveRequest body) {
        String assertion = json.valueToTree(body).path("biometric_assertion").asString(null);
        return action(idempotencyKey, id, "approve", body, () -> threeds.approve(id, assertion));
    }

    @Override
    public ResponseEntity<ThreedsAuthentication> threedsAuthenticationsDecline(
            UUID id, String idempotencyKey, ThreedsAuthenticationsDeclineRequest body) {
        String reason = json.valueToTree(body).path("reason").asString(null);
        return action(idempotencyKey, id, "decline", body, () -> threeds.decline(id, reason));
    }

    /** Cardholder action: exactly-once per Idempotency-Key; the response never carries the CAVV. */
    private ResponseEntity<ThreedsAuthentication> action(String key, UUID id, String action, Object body,
                                                         Supplier<ObjectNode> work) {
        TenantContext.require();
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("action", action);
        fingerprint.put("id", id.toString());
        fingerprint.put("body", body == null ? json.createObjectNode() : json.valueToTree(body));
        return idempotency.execute(key, fingerprint, ThreedsAuthentication.class, () -> {
            ObjectNode row = work.get();
            row.remove("cavv");
            return ResponseEntity.ok(json.treeToValue(row, ThreedsAuthentication.class));
        });
    }

    private void verify(byte[] signed) {
        if (!Crypto.verifySignature(props.callbackSecret(), signed, request.getHeader(SIGNATURE_HEADER))) {
            throw new ApiException(401, "invalid_signature", "X-Signature is missing or does not match the request");
        }
    }

    private static <T> T asTenant(UUID tenant, Callable<T> work) {
        try {
            return TenantContext.callAs(tenant, "directory-server", work);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
