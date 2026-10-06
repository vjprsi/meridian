package io.meridian.insights.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnrichmentRulesTest {
    @Test
    void cleansProcessorPrefixesReferencesAndCountryCodes() {
        assertThat(MerchantNameCleaner.clean("SQ *BLUE BOTTLE COFFEE 1234 LONDON GB")).isEqualTo("Blue Bottle Coffee London");
        assertThat(MerchantNameCleaner.clean("PAYPAL *SPOTIFYAB")).isEqualTo("Spotifyab");
        assertThat(MerchantNameCleaner.clean("AMZN Mktp US*2K4L19Y")).isEqualTo("Amzn Mktp");
        assertThat(MerchantNameCleaner.clean("POS TESCO STORES 3012")).isEqualTo("Tesco Stores");
        assertThat(MerchantNameCleaner.clean("CRV*NETFLIX.COM #889 NL")).isEqualTo("Netflix.com");
        assertThat(MerchantNameCleaner.clean("ACME LTD")).isEqualTo("Acme");
        assertThat(MerchantNameCleaner.clean("123456")).isNull();
        assertThat(MerchantNameCleaner.clean("  ")).isNull();
    }

    @Test
    void merchantRulesMatchByPriority() {
        UUID a = UUID.fromString("00000000-0000-7000-8000-000000000001");
        UUID b = UUID.fromString("00000000-0000-7000-8000-000000000002");
        List<MatchRules.MerchantRule> rules = List.of(
                new MatchRules.MerchantRule(a, "CONTAINS", "netflix", 100),
                new MatchRules.MerchantRule(b, "REGEX", "^crv\\*net", 10));
        assertThat(MatchRules.firstMerchantRule(rules, "CRV*NETFLIX.COM")).get().extracting(MatchRules.MerchantRule::id).isEqualTo(b);
        assertThat(MatchRules.firstMerchantRule(rules, "NETFLIX.COM")).get().extracting(MatchRules.MerchantRule::id).isEqualTo(a);
        assertThat(new MatchRules.MerchantRule(a, "PREFIX", "uber", 1).matches("UBER *TRIP")).isTrue();
        assertThat(new MatchRules.MerchantRule(a, "PREFIX", "uber", 1).matches("X UBER")).isFalse();
        assertThat(MatchRules.regexError("([a")).isNotNull();
        assertThat(MatchRules.regexError("^a.*")).isNull();
    }

    @Test
    void overrideRulesNeedAllGivenCriteria() {
        UUID id = UUID.randomUUID();
        MatchRules.OverrideRule r = new MatchRules.OverrideRule(id, "Acme Supplies", null, new BigDecimal("10"),
                new BigDecimal("500"), 100);
        assertThat(r.matches("acme supplies", "ACME SUPPLIES 22", new BigDecimal("-50"))).isTrue();
        assertThat(r.matches("acme supplies", "ACME", new BigDecimal("-5"))).isFalse();
        assertThat(r.matches("Other", "ACME", new BigDecimal("-50"))).isFalse();
        assertThat(new MatchRules.OverrideRule(id, null, null, null, null, 1).matches("x", "x", BigDecimal.ONE)).isFalse();
        MatchRules.OverrideRule pattern = new MatchRules.OverrideRule(id, null, "gym|fitness", null, null, 1);
        assertThat(pattern.matches(null, "PURE GYM LTD", BigDecimal.TEN)).isTrue();
    }
}
