package io.meridian.productcatalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.meridian.common.error.ApiException;
import io.meridian.productcatalog.domain.definition.DefinitionDiff;
import io.meridian.productcatalog.domain.scripts.ScriptCompiler;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

class ScriptAndDiffTest {
    static final String RUST = """
            #[no_mangle]
            pub extern "C" fn run(balance: i64, rate_bp: i64, days: i64) -> i64 {
                balance * rate_bp * days / 3_650_000
            }
            """;

    @Test
    void compileIsDeterministicAndIgnoresTrailingWhitespace() {
        ScriptCompiler.CompileReport a = ScriptCompiler.compile("RUST", RUST, "run", 10_000_000, 16, 50);
        ScriptCompiler.CompileReport b = ScriptCompiler.compile("RUST", RUST.replace("\n", "   \r\n"), "run", 10_000_000, 16, 50);
        assertThat(a.passed()).isTrue();
        assertThat(a.moduleSha256()).hasSize(64).isEqualTo(b.moduleSha256());
        assertThat(ScriptCompiler.moduleSha("RUST", RUST, "run")).isEqualTo(a.moduleSha256());
        assertThat(ScriptCompiler.compile("RUST", RUST + "// change", "run", 10_000_000, 16, 50).moduleSha256())
                .isNotEqualTo(a.moduleSha256());
    }

    @Test
    void compileRejectsSandboxEscapesMissingEntrypointAndBadBudgets() {
        ScriptCompiler.CompileReport fs = ScriptCompiler.compile("RUST", "use std::fs;\n" + RUST, "run", 10_000_000, 16, 50);
        assertThat(fs.passed()).isFalse();
        assertThat(fs.errors()).anyMatch(e -> e.contains("std::fs"));
        assertThat(ScriptCompiler.compile("TYPESCRIPT", "function run() { return 1; }", "run", 1000, 16, 50).passed()).isFalse();
        assertThat(ScriptCompiler.compile("TYPESCRIPT", "export function run() { return Math.random(); }", "run", 1000, 16, 50)
                .passed()).isFalse();
        assertThat(ScriptCompiler.compile("TINYGO", "//export run\nfunc run() int { return 1 }", "run", 1000, 16, 50).passed()).isTrue();
        assertThat(ScriptCompiler.compile("RUST", RUST, "run", 10_000_000, 1024, 50).passed()).isFalse();
        assertThat(ScriptCompiler.compile("RUST", "pub fn run() { (", "run", 1000, 16, 50).passed()).isFalse();
    }

    @Test
    void testChecksHookContractAndIntegrity() {
        String sha = ScriptCompiler.moduleSha("RUST", RUST, "run");
        ScriptCompiler.TestReport ok = ScriptCompiler.test("ACCRUAL", sha, "RUST", RUST, "run", 10_000_000,
                Map.of("balance", 1000, "rate", "0.05", "days", 30), "{}");
        assertThat(ok.passed()).isTrue();
        ScriptCompiler.TestReport missing = ScriptCompiler.test("ACCRUAL", sha, "RUST", RUST, "run", 10_000_000,
                Map.of("balance", 1000), "{}");
        assertThat(missing.passed()).isFalse();
        ScriptCompiler.TestReport tampered = ScriptCompiler.test("ACCRUAL", "f".repeat(64), "RUST", RUST, "run", 10_000_000,
                Map.of("balance", 1000, "rate", "0.05", "days", 30), "{}");
        assertThat(tampered.errors()).anyMatch(e -> e.contains("compile again"));
    }

    @Property(tries = 100)
    void moduleHashIsAFunctionOfTheSource(@ForAll @AlphaChars @StringLength(min = 1, max = 40) String body) {
        String src = "export function run() { return \"" + body + "\"; }";
        assertThat(ScriptCompiler.compile("ASSEMBLYSCRIPT", src, "run", 1000, 16, 50).moduleSha256())
                .isEqualTo(ScriptCompiler.compile("ASSEMBLYSCRIPT", src, "run", 1000, 16, 50).moduleSha256());
    }

    @Test
    void diffReportsAddedRemovedAndChangedFieldsByBusinessKey() {
        Map<String, Object> v1 = Map.of(
                "parameters", List.of(Map.of("param_key", "dormancy.inactive_days", "param_type", "INTEGER", "value_text", "365")),
                "interest_rates", List.of(Map.of("rate_kind", "CREDIT_INTEREST", "effective_from", "2026-01-01",
                        "rate_type", "FIXED", "fixed_rate", new BigDecimal("0.0400000000"))),
                "fees", List.of(Map.of("code", "MONTHLY", "flat_amount", new BigDecimal("5.00"))),
                "channel_note", "branch only");
        Map<String, Object> v2 = Map.of(
                "parameters", List.of(Map.of("param_key", "dormancy.inactive_days", "param_type", "INTEGER", "value_text", "365")),
                "interest_rates", List.of(Map.of("rate_kind", "CREDIT_INTEREST", "effective_from", "2026-01-01",
                        "rate_type", "FIXED", "fixed_rate", new BigDecimal("0.045"))),
                "fees", List.of(Map.of("code", "ATM", "flat_amount", new BigDecimal("2"))),
                "channel_note", "all channels");
        Map<String, Object> d = DefinitionDiff.diff(v1, v2);
        assertThat(d.get("identical")).isEqualTo(false);
        assertThat(d.get("change_count")).isEqualTo(4);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, List<Object>>> sections = (Map<String, Map<String, List<Object>>>) d.get("sections");
        assertThat(sections.get("parameters").get("changed")).isEmpty();
        assertThat(sections.get("interest_rates").get("changed")).hasSize(1);
        assertThat(sections.get("fees").get("added")).hasSize(1);
        assertThat(sections.get("fees").get("removed")).hasSize(1);
        assertThat(DefinitionDiff.diff(v1, v1).get("identical")).isEqualTo(true);
        // scale-insensitive equality
        Map<String, Object> v1b = Map.of("fees", List.of(Map.of("code", "MONTHLY", "flat_amount", new BigDecimal("5"))));
        Map<String, Object> v1c = Map.of("fees", List.of(Map.of("code", "MONTHLY", "flat_amount", new BigDecimal("5.0000"))));
        assertThat(DefinitionDiff.diff(v1b, v1c).get("identical")).isEqualTo(true);
    }

    @Test
    void stateMachinesRejectInvalidTransitions() {
        assertThat(Lifecycles.VERSION.apply("DRAFT", "submit")).isEqualTo("PENDING_APPROVAL");
        assertThatThrownBy(() -> Lifecycles.VERSION.apply("DRAFT", "publish"))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).status()).isEqualTo(409);
        assertThatThrownBy(() -> Lifecycles.PRODUCT.apply("RETIRED", "suspend")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Lifecycles.requireFourEyes("alice", "alice", "v1"))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("four_eyes_violation");
        assertThat(Lifecycles.SIMULATION.allows("SUCCEEDED", "cancel")).isFalse();
    }

    @Test
    void validatorFlagsOverlappingTiersAndBadFees() {
        Map<String, Object> def = Map.of(
                "interest_rates", List.of(Map.of("rate_kind", "CREDIT_INTEREST", "rate_type", "TIERED", "currency", "USD",
                        "tiers", List.of(Map.of("tier_no", 1, "from_amount", 0, "to_amount", 1000, "rate", "0.01"),
                                Map.of("tier_no", 2, "from_amount", 900, "rate", "0.02")))),
                "fees", List.of(Map.of("code", "MAINT", "calculation_method", "PERCENTAGE", "currency", "EUR")));
        var errors = ConfigValidator.version(def, "USD");
        assertThat(errors).extracting(ApiException.FieldError::code)
                .contains("tiers_not_contiguous", "required", "currency_mismatch");
    }
}
