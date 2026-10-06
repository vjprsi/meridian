package io.meridian.treasurydeal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class IdentifiersTest {

    @Test
    void knownIsinsAreValid() {
        for (String isin : new String[] {"US0378331005", "GB0002634946", "DE000BAY0017", "XS2010028939", "IE00B4L5Y983"}) {
            assertThat(Identifiers.isValidIsin(isin)).as(isin).isTrue();
        }
        assertThat(Identifiers.isinCheckDigit("US037833100")).isEqualTo(5);
    }

    @Test
    void wrongCheckDigitOrShapeIsRejected() {
        assertThat(Identifiers.isValidIsin("US0378331006")).isFalse();
        assertThat(Identifiers.isValidIsin("US037833100")).isFalse();
        assertThat(Identifiers.isValidIsin("us0378331005")).isFalse();
        assertThat(Identifiers.isValidIsin("1S0378331005")).isFalse();
        assertThat(Identifiers.isValidIsin(null)).isFalse();
    }

    @Provide
    Arbitrary<String> isinBodies() {
        return Arbitraries.strings().withChars('A', 'Z').ofLength(2)
                .flatMap(cc -> Arbitraries.strings().withChars("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789").ofLength(9)
                        .map(body -> cc + body));
    }

    @Property
    void computedCheckDigitAlwaysValidatesAndAnyOtherDigitFails(@ForAll("isinBodies") String body) {
        int cd = Identifiers.isinCheckDigit(body);
        assertThat(Identifiers.isValidIsin(body + cd)).isTrue();
        assertThat(Identifiers.isValidIsin(body + ((cd + 1) % 10))).isFalse();
    }

    @Test
    void leiAndBic() {
        assertThat(Identifiers.isValidLei("5493001KJTIIGC8Y1R12")).isTrue();
        assertThat(Identifiers.isValidLei("529900T8BM49AURSDO55")).isTrue();
        assertThat(Identifiers.isValidLei("529900T8BM49AURSDO56")).isFalse();
        assertThat(Identifiers.isValidBic("DEUTDEFF")).isTrue();
        assertThat(Identifiers.isValidBic("DEUTDEFF500")).isTrue();
        assertThat(Identifiers.isValidBic("DEUT1EFF")).isFalse();
        assertThat(Identifiers.isValidBic("DEUTDEF")).isFalse();
    }

    @Provide
    Arbitrary<String> leiBodies() {
        return Arbitraries.strings().withChars("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789").ofLength(18);
    }

    @Property
    void leiWithIso7064CheckDigitsValidates(@ForAll("leiBodies") String body) {
        StringBuilder digits = new StringBuilder();
        for (char c : (body + "00").toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        int check = 98 - new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue();
        assertThat(Identifiers.isValidLei(body + String.format("%02d", check))).isTrue();
    }
}
