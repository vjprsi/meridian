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

    GatewayCallbacks(ChannelProperties props, JsonMapper json, HttpServletRequest request) {
        this.props = props;
        this.json = json;
        this.request = request;
    }

    @SuppressWarnings("unchecked")
    <T> T run(UUID tenantId, Object body, String principal, Callable<T> work) {
        Map<String, Object> canonical = json.convertValue(body, Map.class);
        if (!GatewaySignature.verify(props.gatewaySecret(), canonical, request.getHeader(GatewaySignature.HEADER))) {
            throw new ApiException(401, "invalid_signature", "Missing or invalid " + GatewaySignature.HEADER);
        }
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
