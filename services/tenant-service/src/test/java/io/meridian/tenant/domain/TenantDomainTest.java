package io.meridian.tenant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TenantDomainTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void leiAndBicValidation() {
        assertThat(Validators.isValidLei("5493001KJTIIGC8Y1R12")).isTrue();
        assertThat(Validators.isValidLei("529900T8BM49AURSDO55")).isTrue();
        assertThat(Validators.isValidLei("5493001KJTIIGC8Y1R13")).isFalse();
        assertThat(Validators.isValidLei("TOO-SHORT")).isFalse();
        assertThat(Validators.isValidBic("DEUTDEFF")).isTrue();
        assertThat(Validators.isValidBic("DEUTDEFF500")).isTrue();
        assertThat(Validators.isValidBic("DEUT1EFF")).isFalse();
    }

    @Test
    void contrastAndDurations() {
        assertThat(Validators.contrastRatio("#000000", "#FFFFFF")).isBetween(20.9, 21.1);
        assertThat(Validators.contrastRatio("#FFF", "#FFFFFF")).isEqualTo(1.0);
        assertThat(Validators.isIsoDuration("PT30M")).isTrue();
        assertThat(Validators.isIsoDuration("P1D")).isTrue();
        assertThat(Validators.isIsoDuration("30 minutes")).isFalse();
    }

    @Test
    void settingValuesAreTyped() {
        assertThat(SettingValues.validate("NUMBER", json.readTree("{\"value\":12}"))).isEmpty();
        assertThat(SettingValues.validate("NUMBER", json.readTree("{\"value\":\"abc\"}"))).isPresent();
        assertThat(SettingValues.validate("BOOLEAN", json.readTree("{\"value\":true}"))).isEmpty();
        assertThat(SettingValues.validate("AMOUNT", json.readTree("{\"amount\":\"10.00\",\"currency\":\"KES\"}"))).isEmpty();
        assertThat(SettingValues.validate("AMOUNT", json.readTree("{\"amount\":\"x\",\"currency\":\"KES\"}"))).isPresent();
        assertThat(SettingValues.validate("DURATION", json.readTree("{\"value\":\"PT5M\"}"))).isEmpty();
        assertThat(SettingValues.validate("JSON", json.readTree("\"text\""))).isPresent();
        assertThat(SettingValues.validate("NUMBER", json.readTree("12"))).isPresent();
    }

    @Test
    void lifecycleTransitions() {
        assertThat(Lifecycle.allowed(Lifecycle.TENANT, "PROVISIONING", "ACTIVE")).isTrue();
        assertThat(Lifecycle.allowed(Lifecycle.TENANT, "TERMINATED", "ACTIVE")).isFalse();
        assertThat(Lifecycle.allowed(Lifecycle.CELL, "ACTIVE", "DRAINING")).isTrue();
        assertThat(Lifecycle.allowed(Lifecycle.BRANCH, "CLOSED", "ACTIVE")).isFalse();
    }

    @Test
    void moduleDependencies() {
        assertThat(Modules.missingPrerequisites("CARDS", Set.of("LEDGER"))).containsExactly("DEPOSITS");
        assertThat(Modules.blockingDependents("LEDGER", Set.of("LEDGER", "DEPOSITS", "PAYMENTS")))
                .containsExactly("DEPOSITS", "PAYMENTS");
        assertThat(Modules.missingPrerequisites("RISK", Set.of())).isEmpty();
    }

    @Test
    void flagEvaluationRulesRolloutAndVariants() {
        JsonNode rules = json.readTree("""
                [{"attribute":"country","operator":"in","values":["KE","UG"],"variant":"beta"},
                 {"attribute":"app.version","operator":"semver_lt","value":"2.0.0","serve":false}]""");
        FlagEvaluator.Flag flag = new FlagEvaluator.Flag("new-ui", true, 0, rules, null, null);
        Instant now = Instant.now();
        var ke = FlagEvaluator.evaluate(flag, "customer", "c1", json.readTree("{\"country\":\"KE\"}"), now);
        assertThat(ke.enabled()).isTrue();
        assertThat(ke.variant()).isEqualTo("beta");
        assertThat(ke.reason()).isEqualTo("RULE_MATCH:0");
        var old = FlagEvaluator.evaluate(flag, "customer", "c1",
                json.readTree("{\"country\":\"TZ\",\"app\":{\"version\":\"1.9.3\"}}"), now);
        assertThat(old.enabled()).isFalse();
        assertThat(old.reason()).isEqualTo("RULE_MATCH:1");
        var other = FlagEvaluator.evaluate(flag, "customer", "c1", json.readTree("{\"country\":\"TZ\"}"), now);
        assertThat(other.reason()).isEqualTo("DEFAULT");

        FlagEvaluator.Flag full = new FlagEvaluator.Flag("x", true, 100, null, json.readTree("{\"a\":1,\"b\":1}"), null);
        var r = FlagEvaluator.evaluate(full, "user", "u1", null, now);
        assertThat(r.enabled()).isTrue();
        assertThat(r.variant()).isIn("a", "b");
        assertThat(FlagEvaluator.evaluate(full, "user", "u1", null, now)).isEqualTo(r);

        FlagEvaluator.Flag off = new FlagEvaluator.Flag("x", false, 100, null, null, now.minusSeconds(5));
        var d = FlagEvaluator.evaluate(off, "user", "u1", null, now);
        assertThat(d.enabled()).isFalse();
        assertThat(d.reason()).isEqualTo("DISABLED");
        assertThat(d.expired()).isTrue();

        int on = 0;
        FlagEvaluator.Flag half = new FlagEvaluator.Flag("half", true, 50, null, null, null);
        for (int i = 0; i < 2000; i++) {
            if (FlagEvaluator.evaluate(half, "user", "u" + i, null, now).enabled()) {
                on++;
            }
        }
        assertThat(on).isBetween(850, 1150);
        assertThat(FlagEvaluator.validateRules(json.readTree("[{\"attribute\":\"a\",\"operator\":\"regex\"}]"))).isPresent();
    }
}
