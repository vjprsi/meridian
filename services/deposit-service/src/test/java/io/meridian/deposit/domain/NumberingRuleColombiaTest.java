package io.meridian.deposit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Colombian national account numbers: entity code (3) + office (3) + serial (7) + Luhn, no IBAN. */
class NumberingRuleColombiaTest {

    private static NumberingRule rule(String bank, String branch) {
        return NumberingRule.resolve(new NumberingRule.Input("co", null, bank, branch, null, null, null, null, null, null));
    }

    @Test
    void colombiaUsesTheNationalFormatWithoutIban() {
        NumberingRule co = rule("051", "12");
        assertThat(co.scheme()).isEqualTo(NumberingRule.Scheme.NATIONAL);
        assertThat(co.branchCode()).isEqualTo("012");
        NumberingRule.Numbers n = co.generate(new Random(11));
        assertThat(n.accountNumber()).hasSize(11).startsWith("012");
        assertThat(n.iban()).isNull();
        assertThat(n.bban()).isEqualTo("051" + n.accountNumber());
        assertThat(n.routingCode()).isEqualTo("051012");
        assertThat(AccountNumbers.isValidLuhn("051" + n.accountNumber())).isTrue();
        assertThat(co.isValid(n.accountNumber())).isTrue();
        char[] c = n.accountNumber().toCharArray();
        c[5] = (char) ('0' + (c[5] - '0' + 1) % 10);
        assertThat(co.isValid(new String(c))).isFalse();
    }

    @Test
    void theEntityCodeIsMandatory() {
        assertThatThrownBy(() -> rule(null, "001")).isInstanceOf(NumberingRule.Invalid.class);
    }
}
