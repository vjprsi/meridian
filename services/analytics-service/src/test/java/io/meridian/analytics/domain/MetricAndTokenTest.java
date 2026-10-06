package io.meridian.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetricAndTokenTest {
    static final Set<String> FIELDS = Set.of("status", "outstanding", "dpd", "start_date", "product");
    static final List<Map<String, Object>> ROWS = List.of(
            Map.of("status", "ACTIVE", "outstanding", "100", "dpd", 0, "start_date", "2026-01-05", "product", "a"),
            Map.of("status", "ACTIVE", "outstanding", "300", "dpd", 45, "start_date", "2026-01-20", "product", "b"),
            Map.of("status", "ARREARS", "outstanding", "100", "dpd", 95, "start_date", "2026-02-03T10:00:00Z", "product", "b"),
            Map.of("status", "CLOSED", "outstanding", "0", "dpd", 0, "start_date", "2026-03-15", "product", "c"));

    @Test
    void ratioMetricWithMultiplierAndFilters() {
        MetricSpec par30 = MetricSpec.compile(Map.of("aggregate", "RATIO", "multiplier", 100, "scale", 2,
                "filter", "status != 'CLOSED'",
                "numerator", Map.of("aggregate", "SUM", "value", "outstanding", "filter", "dpd > 30"),
                "denominator", Map.of("aggregate", "SUM", "value", "outstanding")), FIELDS);
        assertThat(par30.evaluate(ROWS, Map.of())).isEqualByComparingTo("80.00");
        assertThat(par30.evaluate(List.of(), Map.of())).isNull(); // zero denominator
        MetricSpec distinct = MetricSpec.compile(Map.of("aggregate", "COUNT_DISTINCT", "value", "product", "scale", 0), FIELDS);
        assertThat(distinct.evaluate(ROWS, Map.of())).isEqualByComparingTo("3");
        MetricSpec avg = MetricSpec.compile(Map.of("aggregate", "AVG", "value", "dpd"), FIELDS);
        assertThat(avg.evaluate(ROWS, Map.of())).isEqualByComparingTo("35.0000");
        assertThatThrownBy(() -> MetricSpec.compile(Map.of("aggregate", "SUM"), FIELDS)).hasMessageContaining("needs a value");
        assertThatThrownBy(() -> MetricSpec.compile(Map.of("aggregate", "MEDIAN", "value", "dpd"), FIELDS)).hasMessageContaining("aggregate must be");
        assertThatThrownBy(() -> MetricSpec.compile(Map.of("aggregate", "SUM", "value", "salary"), FIELDS)).hasMessageContaining("unknown field salary");
        assertThatThrownBy(() -> MetricSpec.compile(Map.of("aggregate", "RATIO", "numerator", Map.of("aggregate", "COUNT")), FIELDS))
                .hasMessageContaining("RATIO needs numerator and denominator");
    }

    @Test
    void seriesBucketsEveryPeriod() {
        MetricSpec count = MetricSpec.compile(Map.of("aggregate", "COUNT", "scale", 0), FIELDS);
        List<Map<String, Object>> months = count.series(ROWS, "start_date", LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-04-30"), "MONTH", Map.of());
        assertThat(months).extracting(p -> p.get("period_start")).containsExactly("2026-01-01", "2026-02-01", "2026-03-01", "2026-04-01");
        assertThat(months).extracting(p -> ((BigDecimal) p.get("value")).intValue()).containsExactly(2, 1, 1, 0);
        List<Map<String, Object>> weeks = count.series(ROWS, "start_date", LocalDate.parse("2026-01-05"),
                LocalDate.parse("2026-01-25"), "WEEK", Map.of());
        assertThat(weeks).extracting(p -> p.get("period_start")).containsExactly("2026-01-05", "2026-01-12", "2026-01-19");
        assertThat(weeks).extracting(p -> ((BigDecimal) p.get("value")).intValue()).containsExactly(1, 0, 1);
    }

    @Test
    void embedTokensAreSignedExpiringAndTenantBound() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String tenant = "0199a000-0000-7000-8000-0000000000a1";
        String dashboard = "0199a000-0000-7000-8000-00000000d001";
        String token = EmbedTokens.issue(key, new EmbedTokens.Claims(tenant, dashboard, "alice", 1000, 1900,
                "0199a000-0000-7000-8000-0000000000f1"));
        EmbedTokens.Verification ok = EmbedTokens.verify(key, token, 1500, tenant);
        assertThat(ok.valid()).isTrue();
        assertThat(ok.claims().dashboardId()).isEqualTo(dashboard);
        assertThat(EmbedTokens.verify(key, token, 1900, tenant).reason()).isEqualTo("expired");
        assertThat(EmbedTokens.verify(key, token, 1500, "0199a000-0000-7000-8000-0000000000a2").reason()).isEqualTo("wrong_tenant");
        assertThat(EmbedTokens.verify("another-key-another-key-another!!".getBytes(StandardCharsets.UTF_8), token, 1500, tenant)
                .reason()).isEqualTo("bad_signature");
        String[] parts = token.split("\\.");
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(java.util.Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
                        .replace("\"exp\":1900", "\"exp\":9900").getBytes(StandardCharsets.UTF_8)) + "." + parts[1];
        assertThat(EmbedTokens.verify(key, forged, 1500, tenant).reason()).isEqualTo("bad_signature");
        assertThat(EmbedTokens.verify(key, "garbage", 1500, tenant).reason()).isEqualTo("malformed");
    }

    @Test
    void shareTargetsValidateAndSummariseWithoutSecrets() {
        assertThat(ShareTargets.validate("S3", Map.of("bucket", "lake", "region", "eu-west-1",
                "role_arn", "arn:aws:iam::1:role/x"))).isEmpty();
        assertThat(ShareTargets.validate("S3", Map.of("bucket", "lake"))).extracting(ShareTargets.Check::name)
                .containsExactly("config.region", "config.role_arn");
        assertThat(ShareTargets.validate("SFTP", Map.of("host", "10.0.0.5", "port", 22, "path", "/in", "username", "u",
                "credentials_ref", "vault:x"))).extracting(ShareTargets.Check::name).containsExactly("config.host");
        assertThat(ShareTargets.validate("SFTP", Map.of("host", "sftp.example.com", "port", 70000, "path", "/in",
                "username", "u", "credentials_ref", "vault:x"))).extracting(ShareTargets.Check::name).containsExactly("config.port");
        assertThat(ShareTargets.validate("ICEBERG_REST", Map.of("uri", "http://catalog.example.com", "warehouse", "w",
                "credentials_ref", "c"))).extracting(ShareTargets.Check::message).containsExactly("uri must use https");
        assertThat(ShareTargets.validate("ICEBERG_REST", Map.of("uri", "https://169.254.169.254/latest", "warehouse", "w",
                "credentials_ref", "c"))).hasSize(1);
        String summary = ShareTargets.summary("S3", Map.of("bucket", "lake", "region", "eu-west-1",
                "secret_access_key", "SUPERSECRET", "prefix", "meridian/"));
        assertThat(summary).contains("lake").contains("eu-west-1").doesNotContain("SUPERSECRET");
        assertThat(ShareTargets.publicHost("localhost")).isFalse();
        assertThat(ShareTargets.publicHost("192.168.1.1")).isFalse();
        assertThat(ShareTargets.publicHost("warehouse.snowflakecomputing.com")).isTrue();
    }

    @Test
    void csvIsEscapedAndInjectionSafe() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        String csv = CsvWriter.write(List.of("a", "b"), json.readTree("[{\"a\":\"=HYPERLINK(1)\",\"b\":\"x,\\\"y\\\"\"},{\"a\":-5,\"b\":null}]"));
        assertThat(csv).isEqualTo("a,b\r\n'=HYPERLINK(1),\"x,\"\"y\"\"\"\r\n-5,\r\n");
    }
}
