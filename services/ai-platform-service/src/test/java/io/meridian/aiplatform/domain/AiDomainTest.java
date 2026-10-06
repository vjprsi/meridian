package io.meridian.aiplatform.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class AiDomainTest {
    private static List<BigDecimal> bins(double... v) {
        List<BigDecimal> out = new ArrayList<>();
        for (double d : v) {
            out.add(BigDecimal.valueOf(d));
        }
        return out;
    }

    @Test
    void psiMatchesTheTextbookFormula() {
        assertThat(Drift.psi(bins(0.25, 0.25, 0.25, 0.25), bins(0.25, 0.25, 0.25, 0.25))).isEqualByComparingTo("0");
        assertThat(Drift.psi(bins(0.1, 0.2, 0.3, 0.4), bins(0.4, 0.3, 0.2, 0.1))).isEqualByComparingTo("0.912870");
        // Counts are normalised: the same shapes give the same PSI.
        assertThat(Drift.psi(bins(10, 20, 30, 40), bins(400, 300, 200, 100))).isEqualByComparingTo("0.912870");
        // Empty bins are floored instead of producing infinity.
        assertThat(Drift.psi(bins(0.5, 0.5, 0), bins(0.5, 0.4, 0.1)).doubleValue()).isFinite().isGreaterThan(0.25);
        assertThat(Drift.stability(new BigDecimal("0.05"))).isEqualTo("STABLE");
        assertThat(Drift.stability(new BigDecimal("0.10"))).isEqualTo("MODERATE");
        assertThat(Drift.stability(new BigDecimal("0.25"))).isEqualTo("SIGNIFICANT");
        assertThatThrownBy(() -> Drift.psi(bins(1, 2), bins(1, 2, 3))).hasMessageContaining("same number of bins");
        assertThatThrownBy(() -> Drift.psi(bins(1), bins(1))).hasMessageContaining("between 2 and");
        assertThatThrownBy(() -> Drift.psi(bins(1, -1), bins(1, 1))).hasMessageContaining("non-negative");
        assertThatThrownBy(() -> Drift.psi(bins(0, 0), bins(1, 1))).hasMessageContaining("all be zero");
    }

    @Property(tries = 200)
    void psiIsNonNegativeAndZeroForIdenticalDistributions(@ForAll @Size(min = 2, max = 20) List<@IntRange(min = 1, max = 1000) Integer> counts) {
        List<BigDecimal> e = counts.stream().map(BigDecimal::valueOf).toList();
        assertThat(Drift.psi(e, e)).isEqualByComparingTo("0");
        List<BigDecimal> shifted = new ArrayList<>(e);
        shifted.set(0, shifted.getFirst().add(BigDecimal.valueOf(500)));
        assertThat(Drift.psi(e, shifted).signum()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void thresholdsFollowTheMetricDirection() {
        assertThat(Drift.breached("PSI", new BigDecimal("0.3"), new BigDecimal("0.25"))).isTrue();
        assertThat(Drift.breached("PSI", new BigDecimal("0.2"), new BigDecimal("0.25"))).isFalse();
        assertThat(Drift.breached("AUC", new BigDecimal("0.65"), new BigDecimal("0.70"))).isTrue();
        assertThat(Drift.breached("AUC", new BigDecimal("0.75"), new BigDecimal("0.70"))).isFalse();
        assertThat(Drift.breached("KS", new BigDecimal("0.1"), null)).isFalse();
    }

    @Test
    void redactsBuiltInEntitiesWithValidation() {
        String text = "Mail ada.obi@example.com, call +234 803 123 4567, card 4111 1111 1111 1111 (not 4111 1111 1111 1112), "
                + "IBAN GB82 WEST 1234 5698 7654 32, SSN 123-45-6789, account 0123456789 from 10.20.30.40.";
        PiiRedactor.Result r = PiiRedactor.redact(text, EnumSet.allOf(PiiRedactor.Entity.class), List.of());
        assertThat(r.text()).isEqualTo("Mail [EMAIL], call [PHONE], card [PAN] (not 4111 1111 1111 1112), "
                + "IBAN [IBAN], SSN [NATIONAL_ID], account [ACCOUNT_NUMBER] from [IP_ADDRESS].");
        assertThat(r.counts()).containsEntry("EMAIL", 1).containsEntry("PAN", 1).containsEntry("IBAN", 1);
        // Only the configured entities are redacted.
        PiiRedactor.Result emailOnly = PiiRedactor.redact(text, PiiRedactor.entities(List.of("EMAIL")), List.of());
        assertThat(emailOnly.text()).contains("[EMAIL]").contains("4111 1111 1111 1111");
        assertThat(PiiRedactor.luhn("4111111111111111")).isTrue();
        assertThat(PiiRedactor.validIban("GB82WEST12345698765432")).isTrue();
        assertThat(PiiRedactor.validIban("GB83WEST12345698765432")).isFalse();
    }

    @Test
    void tenantPatternsAreCompiledSafelyAndBounded() {
        List<String> errors = new ArrayList<>();
        List<PiiRedactor.CustomPattern> ok = PiiRedactor.compile(List.of(Map.of("name", "CUSTOMER_NO", "pattern", "CUS-\\d{6}")), errors);
        assertThat(errors).isEmpty();
        assertThat(PiiRedactor.redact("ref CUS-123456 ok", EnumSet.noneOf(PiiRedactor.Entity.class), ok).text())
                .isEqualTo("ref [CUSTOMER_NO] ok");
        PiiRedactor.compile(List.of(
                Map.of("name", "lower", "pattern", "x"),
                Map.of("name", "EVIL", "pattern", "(a+)+$"),
                Map.of("name", "EMPTY", "pattern", "a*"),
                Map.of("name", "BROKEN", "pattern", "(unclosed"),
                Map.of("name", "LONG", "pattern", "a".repeat(201))), errors);
        assertThat(errors).hasSize(5);
        // A pattern that runs out of its time budget cannot hang the request: the text is withheld (fail closed).
        PiiRedactor.CustomPattern slow = new PiiRedactor.CustomPattern("SLOW", Pattern.compile("(a|aa)+b"));
        PiiRedactor.Result r = PiiRedactor.redact("a".repeat(40) + "c", EnumSet.noneOf(PiiRedactor.Entity.class), List.of(slow), 0);
        assertThat(r.text()).startsWith("[REDACTED");
        assertThat(r.counts()).containsKey("SLOW_TIMEOUT");
    }

    @Test
    void retrieverAnswersWithCitationsAndProposesWriteToolsOnly() {
        List<Retriever.Passage> ctx = List.of(
                new Retriever.Passage("case-1", "AML_CASE", "Alert 1", "Customer made 12 cash deposits below the threshold. Pattern suggests structuring."),
                new Retriever.Passage("doc-2", "POLICY", "Cash policy", "Deposits above 10000 require a currency transaction report."),
                new Retriever.Passage("doc-3", "POLICY", "Holidays", "The branch closes on public holidays."));
        List<Retriever.Tool> tools = List.of(new Retriever.Tool("lookup_customer", "READ", List.of("customer")),
                new Retriever.Tool("file_sar", "WRITE", List.of("suspicious activity report", "file a sar")));
        Retriever.Answer a = Retriever.answer("Why is this a structuring case? Should we file a SAR for these cash deposits?",
                ctx, tools, "AML_CASE", "case-1");
        assertThat(a.grounded()).isTrue();
        assertThat(a.citations()).extracting(Retriever.Citation::sourceId).startsWith("case-1").doesNotContain("doc-3");
        assertThat(a.text()).contains("- Customer made 12 cash deposits below the threshold. [1]").contains("Proposed action: file_sar");
        assertThat(a.action().tool()).isEqualTo("file_sar");
        assertThat(a.action().arguments()).containsEntry("subject_id", "case-1");
        // Deterministic: same input, same answer.
        assertThat(Retriever.answer("Why is this a structuring case? Should we file a SAR for these cash deposits?", ctx, tools,
                "AML_CASE", "case-1").text()).isEqualTo(a.text());
        Retriever.Answer none = Retriever.answer("What is the weather in Lagos?", ctx, tools, null, null);
        assertThat(none.grounded()).isFalse();
        assertThat(none.text()).contains("could not find");
        assertThat(Retriever.estimateTokens("12345678")).isEqualTo(2);
    }
}
