package io.meridian.treasurydeal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfirmationsTest {
    static final LocalDate T = LocalDate.of(2026, 10, 6);

    static Confirmations.MoneyMarket placement() {
        return new Confirmations.MoneyMarket("MM261006-000ABC", "MERIGB2LXXX", "DEUTDEFFXXX", true, T, T, T.plusDays(90),
                "EUR", new BigDecimal("1000000.00"), new BigDecimal("12500.00"), new BigDecimal("0.05"), "ACT_360");
    }

    @Test
    void mt320CarriesTheDealTerms() {
        String text = Confirmations.mt320(placement());
        assertThat(text).contains(":20:MM261006-000ABC", ":17R:L", ":30T:20261006", ":30V:20261006", ":30P:20270104",
                ":32B:EUR1000000,", ":34E:EUR12500,", ":37G:5,", ":14D:ACT/360", ":82A:MERIGB2LXXX", ":87A:DEUTDEFFXXX");
    }

    @Test
    void counterpartysMirrorConfirmationMatchesAndATypoDoesNot() {
        // the counterparty borrows: their MT320 says 17R:B
        Confirmations.MoneyMarket theirs = new Confirmations.MoneyMarket("THEIR-REF", "DEUTDEFFXXX", "MERIGB2LXXX", false, T, T,
                T.plusDays(90), "EUR", new BigDecimal("1000000"), new BigDecimal("12500"), new BigDecimal("0.05"), "ACT_360");
        Map<String, String> parsed = Confirmations.parse("MT320", Confirmations.mt320(theirs));
        assertThat(parsed).containsEntry("party_role", "BORROWER").containsEntry("principal", "1000000")
                .containsEntry("interest_rate", "0.05").containsEntry("maturity_date", "2027-01-04");
        Map<String, String> expected = Confirmations.expected(placement());
        assertThat(Confirmations.match(expected, Confirmations.toOurPerspective(parsed))).isEmpty();

        Map<String, String> typo = new java.util.LinkedHashMap<>(parsed);
        typo.put("principal", "100000");
        typo.put("maturity_date", "2027-01-05");
        List<Confirmations.Mismatch> mm = Confirmations.match(expected, Confirmations.toOurPerspective(typo));
        assertThat(mm).extracting(Confirmations.Mismatch::field).containsExactly("maturity_date", "principal");
        // same role on both sides is a mismatch too
        Map<String, String> sameRole = new java.util.LinkedHashMap<>(parsed);
        sameRole.put("party_role", "LENDER");
        assertThat(Confirmations.match(expected, Confirmations.toOurPerspective(sameRole)))
                .extracting(Confirmations.Mismatch::field).containsExactly("party_role");
    }

    @Test
    void mt300SwapRoundTripFromTheCounterpartysSide() {
        Confirmations.Fx ours = new Confirmations.Fx("FX261006-0000AA", "MERIGB2LXXX", "DEUTDEFFXXX", T, List.of(
                new Confirmations.FxLeg("NEAR", T.plusDays(2), new BigDecimal("1.085"), "EUR", new BigDecimal("1000000"),
                        "USD", new BigDecimal("1085000")),
                new Confirmations.FxLeg("FAR", T.plusDays(92), new BigDecimal("1.0875"), "USD", new BigDecimal("1087500"),
                        "EUR", new BigDecimal("1000000"))), false, null, null);
        String text = Confirmations.mt300(ours);
        assertThat(text).contains(":36:1,085", ":32B:EUR1000000,", ":33B:USD1085000,", ":36:1,0875");
        // the counterparty sells what we buy
        Confirmations.Fx theirs = new Confirmations.Fx("X", "DEUTDEFFXXX", "MERIGB2LXXX", T, List.of(
                new Confirmations.FxLeg("NEAR", T.plusDays(2), new BigDecimal("1.085"), "USD", new BigDecimal("1085000"),
                        "EUR", new BigDecimal("1000000")),
                new Confirmations.FxLeg("FAR", T.plusDays(92), new BigDecimal("1.0875"), "EUR", new BigDecimal("1000000"),
                        "USD", new BigDecimal("1087500"))), false, null, null);
        Map<String, String> parsed = Confirmations.parse("MT300", Confirmations.mt300(theirs));
        assertThat(Confirmations.match(Confirmations.expected(ours), Confirmations.toOurPerspective(parsed))).isEmpty();
        parsed.put("far_rate", "1.0880");
        assertThat(Confirmations.match(Confirmations.expected(ours), Confirmations.toOurPerspective(parsed)))
                .extracting(Confirmations.Mismatch::field).containsExactly("far_rate");
    }
}
