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

    /**
     * IBAN length per country (SWIFT IBAN registry). reference-data-service ({@code country.iban_length}) takes
     * precedence where it has a value; this table is the fallback and the validation reference.
     */
    public static final Map<String, Integer> IBAN_LENGTHS = parseLengths("""
            AD24 AE23 AL28 AT20 AZ28 BA20 BE16 BG22 BH22 BI27 BR29 BY28 CH21 CR22 CY28 CZ24 DE22 DJ27 DK18 DO28 EE20
            EG29 ES24 FI18 FK18 FO18 FR27 GB22 GE22 GI23 GL18 GR27 GT28 HR21 HU28 IE22 IL23 IQ23 IS26 IT27 JO30 KW30
            KZ20 LB28 LC32 LI21 LT20 LU20 LV21 LY25 MC27 MD24 ME22 MK19 MN20 MR27 MT31 MU30 NI28 NL18 NO15 OM23 PK24
            PL28 PS29 PT25 QA29 RO24 RS22 RU33 SA24 SC31 SD18 SE24 SI19 SK24 SM27 SO23 ST25 SV28 TL23 TN24 TR26 UA29
            VA22 VG24 XK20 YE30""");

    private AccountNumbers() {}

    private static Map<String, Integer> parseLengths(String table) {
        Map<String, Integer> m = new java.util.HashMap<>();
        for (String e : table.trim().split("\\s+")) {
            m.put(e.substring(0, 2), Integer.parseInt(e.substring(2)));
        }
        return Map.copyOf(m);
    }

    /** True when the country is an IBAN country in the built-in registry table. */
    public static boolean usesIban(String country) {
        return country != null && IBAN_LENGTHS.containsKey(country.toUpperCase(Locale.ROOT));
    }

    /**
     * ISO 7064 MOD 97-10 national check digits (two digits) over an alphanumeric payload (letters count A=10 .. Z=35),
     * as appended to the payload: {@code payload + checkMod97(payload)} is then divisible by 97.
     */
    public static String checkMod97(String payload) {
        int cd = 98 - mod97(payload + "00");
        return cd < 10 ? "0" + cd : Integer.toString(cd);
    }

    /**
     * NUBAN check digit (CBN Nigeria): weights 3, 7, 3 repeating over bank code + serial, check = 10 - (sum mod 10),
     * 10 becomes 0.
     */
    public static int nubanCheckDigit(String digits) {
        requireDigits(digits);
        int[] w = {3, 7, 3};
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            sum += (digits.charAt(i) - '0') * w[i % 3];
        }
        int cd = 10 - sum % 10;
        return cd == 10 ? 0 : cd;
    }

    /** Payload as decimal digits: letters become their IBAN values (A=10 .. Z=35). */
    public static String numeric(String alnum) {
        StringBuilder numeric = new StringBuilder();
        for (char c : alnum.toUpperCase(Locale.ROOT).toCharArray()) {
            if (Character.isDigit(c)) {
                numeric.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                numeric.append(c - 'A' + 10);
            } else {
                throw new IllegalArgumentException("invalid character " + c);
            }
        }
        return numeric.toString();
    }

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
        return isValidIban(iban, null);
    }

    /**
     * Structure, country length and mod-97 check of an IBAN. {@code expectedLength} (e.g. from reference-data) wins
     * over the built-in registry table; a country in neither is checked for structure and mod 97 only.
     */
    public static boolean isValidIban(String iban, Integer expectedLength) {
        if (iban == null) {
            return false;
        }
        String s = iban.replace(" ", "").toUpperCase(Locale.ROOT);
        if (s.length() < 15 || s.length() > 34 || !s.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]+")) {
            return false;
        }
        Integer len = expectedLength != null && expectedLength > 0 ? expectedLength : IBAN_LENGTHS.get(s.substring(0, 2));
        if (len != null && len != s.length()) {
            return false;
        }
        return mod97(s.substring(4) + s.substring(0, 4)) == 1;
    }

    static int mod97(String alnum) {
        return new BigInteger(numeric(alnum)).mod(BigInteger.valueOf(97)).intValue();
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
