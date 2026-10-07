package io.meridian.deposit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class NumberingRuleTest {

    static NumberingRule rule(String country, String scheme, String bank, String branch, String bic, String pattern,
                              Integer serial, String check, Integer length, Integer ibanLength) {
        return NumberingRule.resolve(new NumberingRule.Input(country, scheme, bank, branch, bic, pattern, serial, check,
                length, ibanLength));
    }

    @Test
    void kenyaUsesTheNationalFormatWithBankAndBranchCodes() {
        NumberingRule ke = rule("ke", null, "68", "1", "DEMOKENA", null, null, null, null, null);
        assertThat(ke.scheme()).isEqualTo(NumberingRule.Scheme.NATIONAL);
        assertThat(ke.branchCode()).isEqualTo("001");
        assertThat(ke.bic()).isEqualTo("DEMOKENAXXX");
        NumberingRule.Numbers n = ke.generate(new Random(7));
        assertThat(n.accountNumber()).hasSize(13).startsWith("001");
        assertThat(n.iban()).isNull();
        assertThat(n.bban()).isEqualTo("68" + n.accountNumber());
        assertThat(n.routingCode()).isEqualTo("68001");
        assertThat(AccountNumbers.isValidLuhn("68" + n.accountNumber())).isTrue();
        assertThat(ke.isValid(n.accountNumber())).isTrue();
        char[] c = n.accountNumber().toCharArray();
        c[6] = (char) ('0' + (c[6] - '0' + 1) % 10);
        assertThat(ke.isValid(new String(c))).isFalse();
        // the bank code is mandatory and must fit the CBK format
        assertThatThrownBy(() -> rule("KE", null, null, "001", null, null, null, null, null, null))
                .isInstanceOf(NumberingRule.Invalid.class)
                .satisfies(e -> assertThat(((NumberingRule.Invalid) e).code()).isEqualTo("account_numbering_not_configured"));
        assertThatThrownBy(() -> rule("KE", null, "MERI", "001", null, null, null, null, null, null))
                .satisfies(e -> assertThat(((NumberingRule.Invalid) e).code()).isEqualTo("account_numbering_invalid"));
        // Kenya has no IBAN
        assertThatThrownBy(() -> rule("KE", "IBAN", "68", "001", null, null, null, null, null, null))
                .hasMessageContaining("no known IBAN length");
    }

    @Test
    void nubanCheckDigitMatchesTheCbnExample() {
        // CBN NUBAN specification example: bank 011, serial 000001457 -> 0000014579
        assertThat(AccountNumbers.nubanCheckDigit("011000001457")).isEqualTo(9);
        NumberingRule ng = rule("NG", null, "11", null, null, null, null, null, null, null);
        assertThat(ng.bankCode()).isEqualTo("011");
        NumberingRule.Numbers n = ng.generate(new Random(1));
        assertThat(n.accountNumber()).hasSize(10);
        assertThat(ng.isValid(n.accountNumber())).isTrue();
        assertThat(ng.isValid("0000014579")).isTrue();
        assertThat(ng.isValid("0000014578")).isFalse();
    }

    @Test
    void gbIbansTakeTheBankCodeFromTheBicAndValidate() {
        NumberingRule gb = rule("GB", null, null, "400515", "DEMOGB2L", null, null, null, null, 22);
        assertThat(gb.scheme()).isEqualTo(NumberingRule.Scheme.IBAN);
        NumberingRule.Numbers n = gb.generate(new Random(3));
        assertThat(n.iban()).hasSize(22).startsWith("GB").contains("DEMO400515").endsWith(n.accountNumber());
        assertThat(AccountNumbers.isValidIban(n.iban())).isTrue();
        assertThat(n.routingCode()).isEqualTo("400515");
        assertThat(n.bic()).isEqualTo("DEMOGB2LXXX");
        assertThatThrownBy(() -> rule("GB", null, null, "400515", null, null, null, null, null, null))
                .hasMessageContaining("bank_code is required");
        assertThatThrownBy(() -> rule("GB", null, "DEM1", "400515", null, null, null, null, null, null))
                .hasMessageContaining("4 letters");
        assertThatThrownBy(() -> rule("GB", null, null, "400515", "DEMOKENA", null, null, null, null, null))
                .hasMessageContaining("belongs to country KE");
        assertThatThrownBy(() -> rule("GB", null, "DEMO", null, null, null, null, null, null, null))
                .hasMessageContaining("branch_code is required");
    }

    @Test
    void ibanCountriesWithoutABuiltInLayoutFillTheRegisteredLength() {
        NumberingRule fr = rule("FR", null, "30006", "00001", null, null, null, null, null, null);
        NumberingRule.Numbers n = fr.generate(new Random(5));
        assertThat(n.iban()).hasSize(27).startsWith("FR");
        assertThat(n.accountNumber()).hasSize(13);
        assertThat(AccountNumbers.isValidIban(n.iban())).isTrue();
        // reference-data length wins: a hypothetical 20-character IBAN country
        NumberingRule xx = rule("XY", null, "123", null, null, null, null, null, null, 20);
        assertThat(xx.generate(new Random(5)).iban()).hasSize(20);
        assertThat(AccountNumbers.isValidIban(xx.generate(new Random(9)).iban(), 20)).isTrue();
        assertThat(AccountNumbers.isValidIban("FR7630006000011234567890189")).isTrue();
        assertThat(AccountNumbers.isValidIban("FR763000600001123456789018")).isFalse(); // registry length 27
    }

    @Test
    void genericNationalFormatIsConfigurable() {
        NumberingRule us = rule("US", null, "021", null, null, "{bank}{serial}{check}", 8, "MOD97", null, null);
        NumberingRule.Numbers n = us.generate(new Random(11));
        assertThat(n.accountNumber()).hasSize(13).startsWith("021");
        assertThat(new BigInteger(n.accountNumber()).mod(BigInteger.valueOf(97)).intValue()).isEqualTo(1);
        assertThat(n.bban()).isEqualTo(n.accountNumber());
        assertThat(us.isValid(n.accountNumber())).isTrue();
        NumberingRule plain = rule("US", null, null, null, null, null, null, null, 12, null);
        assertThat(plain.generate(new Random(2)).accountNumber()).hasSize(12);
        assertThat(plain.generate(new Random(2)).routingCode()).isNull();
        NumberingRule literal = rule("ZA", null, null, null, null, "9{serial}{check}", 6, "LUHN", null, null);
        assertThat(literal.generate(new Random(4)).accountNumber()).startsWith("9").hasSize(8);
        NumberingRule none = rule("ZA", null, null, null, null, "{serial}", 9, "NONE", null, null);
        assertThat(none.generate(new Random(4)).accountNumber()).hasSize(9);
        assertThatThrownBy(() -> rule("US", null, null, null, null, "{serial}{serial}{check}", 4, null, null, null))
                .hasMessageContaining("exactly once");
        assertThatThrownBy(() -> rule("US", null, null, null, null, "{serial}", 4, "LUHN", null, null))
                .hasMessageContaining("{check} once");
        assertThatThrownBy(() -> rule("US", null, null, null, null, "{serial}-{check}", 4, null, null, null))
                .hasMessageContaining("pattern may contain only");
        assertThatThrownBy(() -> rule("US", null, null, null, null, null, null, "CRC", null, null))
                .hasMessageContaining("check_digit must be");
        assertThatThrownBy(() -> rule("US", "SWIFT", null, null, null, null, null, null, null, null))
                .hasMessageContaining("scheme must be");
    }

    @Property(tries = 200)
    void generatedNationalNumbersAlwaysValidate(@ForAll @IntRange(min = 1, max = 15) int serial,
                                                @ForAll @IntRange(min = 0, max = 3) int checkIndex,
                                                @ForAll long seed) {
        String check = new String[] {"LUHN", "MOD97", "NUBAN", "NONE"}[checkIndex];
        String pattern = "NONE".equals(check) ? "{branch}{serial}" : "{branch}{serial}{check}";
        NumberingRule r = rule("TZ", null, "123", "45", null, pattern, serial, check, null, null);
        String number = r.generate(new Random(seed)).accountNumber();
        assertThat(number).startsWith("45");
        assertThat(r.isValid(number)).isTrue();
    }
}
