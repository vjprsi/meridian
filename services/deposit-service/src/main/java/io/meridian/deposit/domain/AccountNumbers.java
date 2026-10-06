package io.meridian.deposit.domain;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Account number and IBAN arithmetic: Luhn (mod 10) check digits for domestic account numbers and ISO 13616
 * mod-97 check digits for IBANs. Pure functions; randomness is injected.
 */
public final class AccountNumbers {

    /** BBAN layout per supported IBAN country: bank-code length, branch-code length, account-number length. */
    public record BbanFormat(int bankLength, boolean bankAlpha, int branchLength, int accountLength) {
        int ibanLength() {
            return 4 + bankLength + branchLength + accountLength;
        }
    }

    public static final Map<String, BbanFormat> IBAN_COUNTRIES = Map.of(
            "GB", new BbanFormat(4, true, 6, 8),
            "IE", new BbanFormat(4, true, 6, 8),
            "DE", new BbanFormat(8, false, 0, 10),
            "NL", new BbanFormat(4, true, 0, 10),
            "AT", new BbanFormat(5, false, 0, 11),
            "CH", new BbanFormat(5, false, 0, 12),
            "BE", new BbanFormat(3, false, 0, 9),
            "LU", new BbanFormat(3, false, 0, 13));

    private AccountNumbers() {}

    /** Luhn check digit for a string of decimal digits. */
    public static int luhnCheckDigit(String digits) {
        requireDigits(digits);
        int sum = 0;
        boolean dbl = true; // rightmost payload digit is doubled (check digit will be appended to its right)
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }

    public static String withLuhn(String digits) {
        return digits + luhnCheckDigit(digits);
    }

    public static boolean isValidLuhn(String number) {
        if (number == null || number.length() < 2 || !number.chars().allMatch(Character::isDigit)) {
            return false;
        }
        String payload = number.substring(0, number.length() - 1);
        return luhnCheckDigit(payload) == number.charAt(number.length() - 1) - '0';
    }

    /** A random account number of {@code length} digits (last one a Luhn check digit), never starting with 0. */
    public static String generate(int length, RandomGenerator random) {
        if (length < 2 || length > 30) {
            throw new IllegalArgumentException("account number length must be 2..30");
        }
        StringBuilder sb = new StringBuilder(length);
        sb.append(1 + random.nextInt(9));
        for (int i = 1; i < length - 1; i++) {
            sb.append(random.nextInt(10));
        }
        return withLuhn(sb.toString());
    }

    /** Account number length used for a booking country (BBAN account part where IBAN applies). */
    public static int accountLength(String country, int defaultLength) {
        BbanFormat f = country == null ? null : IBAN_COUNTRIES.get(country.toUpperCase(Locale.ROOT));
        return f == null ? defaultLength : f.accountLength();
    }

    /**
     * Builds an IBAN when the country uses IBANs: country + 2 check digits + bank code + branch code + account.
     * Bank/branch codes are left-padded (digits) and validated against the country's BBAN format.
     */
    public static Optional<String> iban(String country, String bankCode, String branchCode, String accountNumber) {
        if (country == null) {
            return Optional.empty();
        }
        String cc = country.toUpperCase(Locale.ROOT);
        BbanFormat f = IBAN_COUNTRIES.get(cc);
        if (f == null) {
            return Optional.empty();
        }
        String bank = fit(bankCode == null ? "" : bankCode.toUpperCase(Locale.ROOT), f.bankLength(), f.bankAlpha() ? 'X' : '0');
        String branch = fit(branchCode == null ? "" : branchCode, f.branchLength(), '0');
        String account = fit(accountNumber, f.accountLength(), '0');
        String bban = bank + branch + account;
        return Optional.of(cc + checkDigits(cc, bban) + bban);
    }

    /** ISO 7064 mod 97-10 check digits for a country + BBAN. */
    public static String checkDigits(String country, String bban) {
        int mod = mod97(bban + country + "00");
        int cd = 98 - mod;
        return cd < 10 ? "0" + cd : Integer.toString(cd);
    }

    public static boolean isValidIban(String iban) {
        if (iban == null) {
            return false;
        }
        String s = iban.replace(" ", "").toUpperCase(Locale.ROOT);
        if (s.length() < 15 || s.length() > 34 || !s.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]+")) {
            return false;
        }
        BbanFormat f = IBAN_COUNTRIES.get(s.substring(0, 2));
        if (f != null && f.ibanLength() != s.length()) {
            return false;
        }
        return mod97(s.substring(4) + s.substring(0, 4)) == 1;
    }

    static int mod97(String alnum) {
        StringBuilder numeric = new StringBuilder();
        for (char c : alnum.toCharArray()) {
            if (Character.isDigit(c)) {
                numeric.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                numeric.append(c - 'A' + 10);
            } else {
                throw new IllegalArgumentException("invalid IBAN character " + c);
            }
        }
        return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue();
    }

    private static String fit(String value, int length, char pad) {
        if (length == 0) {
            return "";
        }
        if (value.length() > length) {
            throw new IllegalArgumentException("'" + value + "' is longer than " + length + " characters");
        }
        return String.valueOf(pad).repeat(length - value.length()) + value;
    }

    private static void requireDigits(String digits) {
        if (digits == null || digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("digits expected: " + digits);
        }
    }
}
