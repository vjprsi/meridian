package io.meridian.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessClientBindingTest {
    static final String BIZ = "0199a000-0000-7000-8000-0000000b0001";
    static final String USER = "0199a000-0000-7000-8000-0000000b0a01";

    private static String code(String biz, String user, String type, List<String> scopes) {
        return BusinessClientBinding.problem(biz, user, type, scopes).map(p -> p[0]).orElse(null);
    }

    @Test
    void aBusinessClientIsAServerClientWithTheBusinessScopeOnly() {
        assertThat(code(BIZ, USER, "SERVER", List.of("channels:business"))).isNull();
        assertThat(code(BIZ, USER, "SERVER", List.of("channels:business", "payments:write")))
                .isEqualTo("business_client_scopes");
        assertThat(code(BIZ, USER, "PUBLIC_APP", List.of("channels:business"))).isEqualTo("business_client_type");
        assertThat(code(BIZ, null, "SERVER", List.of("channels:business"))).isEqualTo("business_binding_incomplete");
        assertThat(code(null, USER, "SERVER", List.of("payments:read"))).isEqualTo("business_binding_incomplete");
    }

    @Test
    void theBusinessScopeNeedsABinding() {
        assertThat(code(null, null, "SERVER", List.of("channels:business"))).isEqualTo("business_binding_required");
        assertThat(code(null, null, "SERVER", List.of("payments:read"))).isNull();
    }
}
