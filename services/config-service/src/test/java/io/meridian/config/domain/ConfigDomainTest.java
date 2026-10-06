package io.meridian.config.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure domain rules: lifecycle, JSON schema subset, diff/checksum, semantic versions. */
class ConfigDomainTest {
    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void lifecycleAllowsOnlyDefinedTransitions() {
        assertThat(ConfigLifecycle.canTransition(ConfigLifecycle.Status.DRAFT, ConfigLifecycle.Status.PENDING_APPROVAL)).isTrue();
        assertThat(ConfigLifecycle.canTransition(ConfigLifecycle.Status.DRAFT, ConfigLifecycle.Status.ACTIVE)).isFalse();
        assertThat(ConfigLifecycle.canTransition(ConfigLifecycle.Status.SUPERSEDED, ConfigLifecycle.Status.ACTIVE)).isFalse();
        assertThatThrownBy(() -> ConfigLifecycle.requireRollbackTarget("ACTIVE"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_transition"));
    }

    @Test
    void schemaSubsetReportsPaths() {
        JsonNode schema = json.readTree("""
                {"type":"object","required":["a","b"],"additionalProperties":false,
                 "properties":{"a":{"type":"integer","maximum":10},"b":{"type":"string","pattern":"^x"},
                   "c":{"type":"array","items":{"enum":["p","q"]}}}}""");
        assertThat(JsonSchemaValidator.validate(schema, json.readTree("{\"a\":5,\"b\":\"xy\",\"c\":[\"p\"]}"))).isEmpty();
        var errors = JsonSchemaValidator.validate(schema, json.readTree("{\"a\":11,\"c\":[\"z\"],\"d\":1}"));
        assertThat(errors).extracting(ApiException.FieldError::field)
                .containsExactlyInAnyOrder("$.b", "$.a", "$.c[0]", "$.d");
    }

    @Test
    void diffAndChecksumIgnoreKeyOrder() {
        JsonNode a = json.readTree("{\"x\":1,\"y\":{\"z\":2}}");
        JsonNode b = json.readTree("{\"y\":{\"z\":2},\"x\":1}");
        assertThat(JsonDiff.diff(json, a, b)).isEmpty();
        assertThat(JsonDiff.checksum(json, a)).isEqualTo(JsonDiff.checksum(json, b));
        assertThat(JsonDiff.diff(json, a, json.readTree("{\"x\":1,\"y\":{\"z\":3},\"w\":true}")).toString())
                .contains("\"op\":\"add\",\"path\":\"/w\"").contains("\"op\":\"replace\",\"path\":\"/y/z\"");
    }

    @Test
    void semverOrdering() {
        assertThat(SemVer.parse("1.10.0")).isGreaterThan(SemVer.parse("1.9.3"));
        assertThat(SemVer.parse("2.0.0")).isGreaterThan(SemVer.parse("2.0.0-rc.1"));
        assertThatThrownBy(() -> SemVer.parse("abc")).isInstanceOf(ApiException.class);
    }
}
