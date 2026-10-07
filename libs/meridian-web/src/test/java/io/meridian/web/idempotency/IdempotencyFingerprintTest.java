package io.meridian.web.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.common.tenancy.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/** SEC-19: the stored request hash distinguishes operation, resource, caller and body. */
class IdempotencyFingerprintTest {
    static final UUID TENANT = UUID.fromString("0199a000-0000-7000-8000-00000000000a");
    final Idempotency idem = new Idempotency(null, JsonMapper.builder().build(), null);
    final Map<String, String> body = Map.of("reason", "review");

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        TenantContext.clear();
    }

    String fp(String method, String path, String principal, Object request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(method, path)));
        TenantContext.set(TENANT, principal);
        return idem.fingerprint(request);
    }

    @Test
    void sameOperationResourceCallerAndBodyMatch() {
        assertThat(fp("POST", "/v1/payments/a/approve", "anna", body)).isEqualTo(fp("POST", "/v1/payments/a/approve", "anna", body));
    }

    @Test
    void anyDifferenceChangesTheFingerprint() {
        String base = fp("POST", "/v1/payments/a/approve", "anna", body);
        assertThat(fp("POST", "/v1/payments/b/approve", "anna", body)).as("other resource id").isNotEqualTo(base);
        assertThat(fp("POST", "/v1/payments/a/reject", "anna", body)).as("other operation").isNotEqualTo(base);
        assertThat(fp("PUT", "/v1/payments/a/approve", "anna", body)).as("other method").isNotEqualTo(base);
        assertThat(fp("POST", "/v1/payments/a/approve", "bob", body)).as("other caller").isNotEqualTo(base);
        assertThat(fp("POST", "/v1/payments/a/approve", "anna", Map.of("reason", "x"))).as("other body").isNotEqualTo(base);
    }
}
