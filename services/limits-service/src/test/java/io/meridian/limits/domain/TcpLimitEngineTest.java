package io.meridian.limits.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.limits.domain.LimitModel.CheckRequest;
import io.meridian.limits.domain.LimitModel.Limit;
import io.meridian.limits.domain.LimitModel.Rule;
import io.meridian.limits.domain.LimitModel.Usage;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** WE-LIMIT-1 engine behaviour: both tiers apply, cross-account aggregation, direction isolation, instrument checks. */
class TcpLimitEngineTest {
    static final UUID WALLET_A = UUID.randomUUID();
    static final UUID WALLET_B = UUID.randomUUID();
    static final UUID REG_SUBJECT = UUID.randomUUID(); // deterministic reg-profile counting subject

    static Rule cumulative(String code) {
        return new Rule(UUID.randomUUID(), code, "REGULATORY_PROFILE", "KYC_T2", null, null, "DAILY", "AMOUNT", "USD",
                new BigDecimal("5000"), null, "REJECT", 100, true, "DEBIT", null, null, null, null, null, null, null,
                null, null);
    }

    static Rule instrument(String code) {
        return new Rule(UUID.randomUUID(), code, "INSTRUMENT_GRADE", "WALLET", null, null, "DAILY", "AMOUNT", "USD",
                new BigDecimal("1000"), null, "REJECT", 100, false, "DEBIT", null, null, "GOLD", "WALLET", null, null,
                null, null, null);
    }

    static CheckRequest debit(UUID wallet) {
        return new CheckRequest("WALLET", wallet, null, null, null, null, "P2P", "MOBILE_APP", new BigDecimal("100"),
                "USD", new BigDecimal("400"), "DEBIT", "KYC_T2", REG_SUBJECT, null, null, "GOLD", "WALLET");
    }

    @Test
    void bothTiersApplyAndRegulatoryIsNeverSuperseded() {
        var sel = LimitEngine.select(List.of(cumulative("CUM"), instrument("ITC")), debit(WALLET_A));
        assertThat(sel.effective()).extracting(a -> a.rule().code()).containsExactlyInAnyOrder("CUM", "ITC");
        assertThat(sel.superseded()).isEmpty();
        var cum = sel.effective().stream().filter(a -> a.rule().code().equals("CUM")).findFirst().orElseThrow();
        assertThat(cum.countingSubjectType()).isEqualTo("REGULATORY_PROFILE");
        assertThat(cum.countingSubjectId()).isEqualTo(REG_SUBJECT);
        var itc = sel.effective().stream().filter(a -> a.rule().code().equals("ITC")).findFirst().orElseThrow();
        assertThat(itc.countingSubjectId()).isEqualTo(WALLET_A); // instrument counts per instrument subject
    }

    @Test
    void cumulativeAggregatesAcrossAccountGroupsByRegulatoryProfile() {
        var selA = LimitEngine.select(List.of(cumulative("CUM")), debit(WALLET_A));
        var selB = LimitEngine.select(List.of(cumulative("CUM")), debit(WALLET_B));
        UUID a = selA.effective().getFirst().countingSubjectId();
        UUID b = selB.effective().getFirst().countingSubjectId();
        assertThat(a).isEqualTo(b).isEqualTo(REG_SUBJECT); // different wallets, one cumulative counter
    }

    @Test
    void directionIsolation() {
        Rule credit = new Rule(UUID.randomUUID(), "CRED", "REGULATORY_PROFILE", "KYC_T2", null, null, "DAILY", "AMOUNT",
                "USD", new BigDecimal("5000"), null, "REJECT", 100, true, "CREDIT", null, null, null, null, null, null,
                null, null, null);
        Rule classic = new Rule(UUID.randomUUID(), "CLASSIC", "GLOBAL", null, null, null, "DAILY", "AMOUNT", "USD",
                new BigDecimal("9000"), null, "REJECT", 100, false);
        // A DEBIT check sees the classic rule but not the CREDIT rule.
        var debit = LimitEngine.select(List.of(credit, classic), debit(WALLET_A));
        assertThat(debit.effective()).extracting(x -> x.rule().code()).containsExactly("CLASSIC");
        // A CREDIT check sees only the CREDIT rule, never the classic (direction-less) rule.
        CheckRequest creditReq = new CheckRequest("WALLET", WALLET_B, null, null, null, null, "P2P", "MOBILE_APP",
                new BigDecimal("100"), "USD", null, "CREDIT", "KYC_T2", REG_SUBJECT, null, null, null, "WALLET");
        var creditSel = LimitEngine.select(List.of(credit, classic), creditReq);
        assertThat(creditSel.effective()).extracting(x -> x.rule().code()).containsExactly("CRED");
    }

    @Test
    void instrumentPerTransactionChecks() {
        Rule r = new Rule(UUID.randomUUID(), "TXN", "INSTRUMENT_GRADE", "WALLET", null, null, "PER_TRANSACTION",
                "AMOUNT", "USD", new BigDecimal("1000"), null, "REJECT", 100, false, "DEBIT", null, null, null, "WALLET",
                null, new BigDecimal("0.5"), new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("20"));
        Limit limit = new Limit(new BigDecimal("1000"), null, null);
        // ok: 100 <= max, >= min 20, multiple of 10, <= 50% of balance 400, leaves 300 >= 5
        assertThat(LimitEngine.evaluate(r, limit, Usage.ZERO, new BigDecimal("100"), new BigDecimal("400")).breached()).isFalse();
        // below per-txn min 20
        assertThat(LimitEngine.evaluate(r, limit, Usage.ZERO, new BigDecimal("10"), new BigDecimal("400")).breached()).isTrue();
        // not a multiple of 10
        assertThat(LimitEngine.evaluate(r, limit, Usage.ZERO, new BigDecimal("25"), new BigDecimal("400")).breached()).isTrue();
        // exceeds 50% of balance (100 > 0.5*150=75)
        assertThat(LimitEngine.evaluate(r, limit, Usage.ZERO, new BigDecimal("100"), new BigDecimal("150")).breached()).isTrue();
        // would leave less than min remaining 5 (balance 100 - 100 = 0 < 5)
        assertThat(LimitEngine.evaluate(r, limit, Usage.ZERO, new BigDecimal("100"), new BigDecimal("100")).breached()).isTrue();
    }
}
