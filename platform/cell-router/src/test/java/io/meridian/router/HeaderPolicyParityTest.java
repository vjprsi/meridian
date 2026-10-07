package io.meridian.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * SEC-13 / SEC-16: the router strips exactly what the edge gateway strips. Its {@link HeaderPolicy} is a copy of the
 * gateway's (two deployables, no shared web library); this test fails when the header sets drift apart.
 */
class HeaderPolicyParityTest {

    @Test
    void routerAndGatewayStripTheSameHeaders() throws Exception {
        String gateway = Files.readString(Path.of("../edge-gateway/src/main/java/io/meridian/gateway/HeaderPolicy.java"));
        String router = Files.readString(Path.of("src/main/java/io/meridian/router/HeaderPolicy.java"));
        for (String set : new String[] {"HOP_BY_HOP", "FORWARDING", "INTERNAL"}) {
            assertThat(set(router, set)).as(set).isEqualTo(set(gateway, set));
        }
        HeaderPolicy jwt = new HeaderPolicy("jwt", java.util.List.of());
        for (String h : HeaderPolicy.INTERNAL) {
            assertThat(jwt.forward(h)).as(h).isFalse();
        }
        assertThat(jwt.forward("Authorization")).isTrue();
        assertThat(new HeaderPolicy("dev", java.util.List.of()).forward("X-Tenant-Id")).isTrue();
    }

    private static String set(String source, String name) {
        Matcher m = Pattern.compile("static final Set<String>\\s+" + name + "\\s*=\\s*Set\\.of\\(([^;]*)\\);",
                Pattern.DOTALL).matcher(source);
        assertThat(m.find()).as(name).isTrue();
        return m.group(1).replaceAll("\\s+", "");
    }
}
