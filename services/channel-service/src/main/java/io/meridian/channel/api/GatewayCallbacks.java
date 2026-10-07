package io.meridian.channel.api;

import io.meridian.channel.config.ChannelProperties;
import io.meridian.channel.domain.GatewaySignature;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Public gateway callbacks (no bearer token, no Idempotency-Key): the signature is verified before any tenant data is
 * touched, then the work runs as the tenant named in the registered callback URL.
 */
@Component
class GatewayCallbacks {
    private final ChannelProperties props;
    private final JsonMapper json;
    private final HttpServletRequest request;
    private final io.meridian.web.callback.CallbackVerifier callbacks;

    GatewayCallbacks(ChannelProperties props, JsonMapper json, HttpServletRequest request,
                     io.meridian.web.callback.CallbackVerifier callbacks) {
        this.props = props;
        this.json = json;
        this.request = request;
        this.callbacks = callbacks;
    }

    @SuppressWarnings("unchecked")
    <T> T run(UUID tenantId, Object body, String principal, Callable<T> work) {
        Map<String, Object> canonical = json.convertValue(body, Map.class);
        String secret = props.gatewaySecret();
        if (secret == null || secret.isBlank()) {
            throw new ApiException(401, "invalid_signature", "Missing or invalid " + GatewaySignature.HEADER);
        }
        // SEC-17: v2 binds timestamp, nonce and the ?tenant_id of the callback URL (no longer an unsigned choice)
        callbacks.verify("gateway:" + principal, tenantId == null ? null : tenantId.toString(),
                GatewaySignature.canonical(canonical).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                request.getHeader(GatewaySignature.HEADER), io.meridian.web.callback.CallbackVerifier.Headers.of(request),
                b -> GatewaySignature.signBytes(secret, b));
        if (tenantId == null) {
            throw ApiException.badRequest("missing_parameter", "tenant_id is required");
        }
        try {
            return TenantContext.callAs(tenantId, principal, work);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
