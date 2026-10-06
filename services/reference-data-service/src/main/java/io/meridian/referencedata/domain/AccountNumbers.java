package io.meridian.referencedata.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** IBAN (ISO 13616, mod-97 per ISO 7064) and BIC (ISO 9362) validation and parsing. Pure functions. */
public final class AccountNumbers {
    private static final Pattern BIC = Pattern.compile("^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$");
    private static final Pattern IBAN_SHAPE = Pattern.compile("^[A-Z]{2}[0-9]{2}[A-Z0-9]{1,30}$");
    private static final Pattern DOMESTIC = Pattern.compile("^[0-9A-Z]{4,34}$");

    /** IBAN length per country (SWIFT IBAN registry), used when the country table has no iban_length. */
    public static final Map<String, Integer> IBAN_LENGTHS = Map.ofEntries(
            Map.entry("AD", 24), Map.entry("AE", 23), Map.entry("AT", 20), Map.entry("BE", 16), Map.entry("BG", 22),
            Map.entry("BH", 22), Map.entry("BR", 29), Map.entry("CH", 21), Map.entry("CY", 28), Map.entry("CZ", 24),
            Map.entry("DE", 22), Map.entry("DK", 18), Map.entry("EE", 20), Map.entry("EG", 29), Map.entry("ES", 24),
            Map.entry("FI", 18), Map.entry("FR", 27), Map.entry("GB", 22), Map.entry("GR", 27), Map.entry("HR", 21),
            Map.entry("HU", 28), Map.entry("IE", 22), Map.entry("IL", 23), Map.entry("IS", 26), Map.entry("IT", 27),
            Map.entry("JO", 30), Map.entry("KW", 30), Map.entry("KZ", 20), Map.entry("LB", 28), Map.entry("LI", 21),
            Map.entry("LT", 20), Map.entry("LU", 20), Map.entry("LV", 21), Map.entry("MC", 27), Map.entry("MT", 31),
            Map.entry("MU", 30), Map.entry("NL", 18), Map.entry("NO", 15), Map.entry("PK", 24), Map.entry("PL", 28),
            Map.entry("PT", 25), Map.entry("QA", 29), Map.entry("RO", 24), Map.entry("RS", 22), Map.entry("SA", 24),
            Map.entry("SE", 24), Map.entry("SI", 19), Map.entry("SK", 24), Map.entry("SM", 27), Map.entry("TN", 24),
            Map.entry("TR", 26), Map.entry("UA", 29));

    /**
     * Position of the national clearing / bank code inside the IBAN (0-based offset, length), where it is a
     * domestic routing code that can be looked up in the bank directory.
     */
    private static final Map<String, int[]> CLEARING_CODE_POS = Map.ofEntries(
            Map.entry("DE", new int[] {4, 8}), Map.entry("GB", new int[] {8, 6}), Map.entry("IE", new int[] {8, 6}),
            Map.entry("FR", new int[] {4, 5}), Map.entry("ES", new int[] {4, 4}), Map.entry("IT", new int[] {5, 5}),
            Map.entry("AT", new int[] {4, 5}), Map.entry("CH", new int[] {4, 5}), Map.entry("BE", new int[] {4, 3}),
            Map.entry("PT", new int[] {4, 4}), Map.entry("PL", new int[] {4, 8}), Map.entry("SE", new int[] {4, 3}));

    /** Countries whose IBAN carries the first four letters of the bank's BIC instead of a numeric code. */
    private static final Map<String, int[]> BIC_PREFIX_POS = Map.of(
            "GB", new int[] {4, 4}, "IE", new int[] {4, 4}, "NL", new int[] {4, 4});

    private AccountNumbers() {}

    public record Issue(String code, String message) {}

    public record IbanCheck(String normalized, String country, String clearingCode, String bicBankCode, List<Issue> issues) {
        public boolean valid() {
            return issues.isEmpty();
        }
    }

    /** Uppercase with spaces and dashes removed. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    /** True when the value has the shape of an IBAN (two letters, two check digits). */
    public static boolean looksLikeIban(String normalized) {
        return normalized.length() >= 5 && Character.isLetter(normalized.charAt(0)) && Character.isLetter(normalized.charAt(1))
                && Character.isDigit(normalized.charAt(2)) && Character.isDigit(normalized.charAt(3));
    }

    public static boolean isValidBic(String bic) {
        return bic != null && BIC.matcher(bic.trim().toUpperCase(Locale.ROOT)).matches();
    }

    /**
     * Validates an IBAN.
     *
     * @param expectedCountry country the account must belong to (may be null)
     * @param expectedLength  IBAN length for the country (null = built-in registry)
     */
    public static IbanCheck checkIban(String raw, String expectedCountry, Integer expectedLength) {
        String iban = normalize(raw);
        List<Issue> issues = new ArrayList<>();
        if (!IBAN_SHAPE.matcher(iban).matches()) {
            issues.add(new Issue("invalid_iban_format", "IBAN must be 2 letters, 2 check digits and up to 30 letters/digits"));
            return new IbanCheck(iban, iban.length() >= 2 ? iban.substring(0, 2) : null, null, null, issues);
        }
        String country = iban.substring(0, 2);
        if (expectedCountry != null && !expectedCountry.equalsIgnoreCase(country)) {
            issues.add(new Issue("iban_country_mismatch", "IBAN country " + country + " does not match " + expectedCountry));
        }
        Integer length = expectedLength != null ? expectedLength : IBAN_LENGTHS.get(country);
        if (length == null) {
            issues.add(new Issue("iban_not_supported", "Country " + country + " does not use IBAN"));
        } else if (iban.length() != length) {
            issues.add(new Issue("invalid_iban_length", "IBAN for " + country + " must have " + length + " characters, got " + iban.length()));
        }
        if (mod97(iban) != 1) {
            issues.add(new Issue("invalid_iban_checksum", "IBAN check digits are wrong (mod-97 check failed)"));
        }
        return new IbanCheck(iban, country, extract(iban, CLEARING_CODE_POS.get(country)),
                extract(iban, BIC_PREFIX_POS.get(country)), issues);
    }

    /** ISO 7064 MOD 97-10 over the rearranged IBAN (letters A=10 .. Z=35). */
    public static int mod97(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder(rearranged.length() * 2);
        for (char ch : rearranged.toCharArray()) {
            if (Character.isDigit(ch)) {
                digits.append(ch);
            } else if (ch >= 'A' && ch <= 'Z') {
                digits.append(ch - 'A' + 10);
            } else {
                return -1;
            }
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue();
    }

    /** Domestic (non-IBAN) account number: 4-34 letters/digits after normalisation. */
    public static List<Issue> checkDomestic(String raw) {
        String n = normalize(raw);
        if (!DOMESTIC.matcher(n).matches()) {
            return List.of(new Issue("invalid_account_number", "Account number must be 4-34 letters or digits"));
        }
        return List.of();
    }

    private static String extract(String iban, int[] pos) {
        if (pos == null || iban.length() < pos[0] + pos[1]) {
            return null;
        }
        return iban.substring(pos[0], pos[0] + pos[1]);
    }
}
