package io.meridian.payment.domain;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** ISO 13616 IBAN: normalisation, country length table, MOD 97-10 check digits and bank-code extraction. */
public final class Iban {
    /** IBAN length per country (SWIFT IBAN registry). */
    static final Map<String, Integer> LENGTHS = Map.ofEntries(
            Map.entry("AD", 24), Map.entry("AE", 23), Map.entry("AL", 28), Map.entry("AT", 20), Map.entry("AZ", 28),
            Map.entry("BA", 20), Map.entry("BE", 16), Map.entry("BG", 22), Map.entry("BH", 22), Map.entry("BR", 29),
            Map.entry("CH", 21), Map.entry("CR", 22), Map.entry("CY", 28), Map.entry("CZ", 24), Map.entry("DE", 22),
            Map.entry("DK", 18), Map.entry("DO", 28), Map.entry("EE", 20), Map.entry("EG", 29), Map.entry("ES", 24),
            Map.entry("FI", 18), Map.entry("FO", 18), Map.entry("FR", 27), Map.entry("GB", 22), Map.entry("GE", 22),
            Map.entry("GI", 23), Map.entry("GL", 18), Map.entry("GR", 27), Map.entry("GT", 28), Map.entry("HR", 21),
            Map.entry("HU", 28), Map.entry("IE", 22), Map.entry("IL", 23), Map.entry("IQ", 23), Map.entry("IS", 26),
            Map.entry("IT", 27), Map.entry("JO", 30), Map.entry("KW", 30), Map.entry("KZ", 20), Map.entry("LB", 28),
            Map.entry("LC", 32), Map.entry("LI", 21), Map.entry("LT", 20), Map.entry("LU", 20), Map.entry("LV", 21),
            Map.entry("MC", 27), Map.entry("MD", 24), Map.entry("ME", 22), Map.entry("MK", 19), Map.entry("MR", 27),
            Map.entry("MT", 31), Map.entry("MU", 30), Map.entry("NL", 18), Map.entry("NO", 15), Map.entry("PK", 24),
            Map.entry("PL", 28), Map.entry("PS", 29), Map.entry("PT", 25), Map.entry("QA", 29), Map.entry("RO", 24),
            Map.entry("RS", 22), Map.entry("SA", 24), Map.entry("SC", 31), Map.entry("SE", 24), Map.entry("SI", 19),
            Map.entry("SK", 24), Map.entry("SM", 27), Map.entry("TL", 23), Map.entry("TN", 24), Map.entry("TR", 26),
            Map.entry("UA", 29), Map.entry("VA", 22), Map.entry("VG", 24), Map.entry("XK", 20));

    /** Offset and length of the bank (and branch) identifier inside the BBAN, for the common countries. */
    private static final Map<String, int[]> BANK_CODE = Map.ofEntries(
            Map.entry("GB", new int[] {0, 10}),  // 4-letter bank code + 6-digit sort code
            Map.entry("IE", new int[] {0, 10}),
            Map.entry("DE", new int[] {0, 8}),   // Bankleitzahl
            Map.entry("NL", new int[] {0, 4}),
            Map.entry("FR", new int[] {0, 10}),  // code banque + code guichet
            Map.entry("ES", new int[] {0, 8}),
            Map.entry("IT", new int[] {1, 11}),  // ABI + CAB after CIN
            Map.entry("BE", new int[] {0, 3}),
            Map.entry("AT", new int[] {0, 5}),
            Map.entry("CH", new int[] {0, 5}));

    private Iban() {}

    /** Upper-case, spaces removed. */
    public static String normalise(String raw) {
        return raw == null ? null : raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String raw) {
        return validate(raw).isEmpty();
    }

    /** Empty when valid, otherwise a stable error code. */
    public static Optional<String> validate(String raw) {
        String iban = normalise(raw);
        if (iban == null || iban.length() < 15 || iban.length() > 34) {
            return Optional.of("invalid_iban_length");
        }
        if (!iban.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]+")) {
            return Optional.of("invalid_iban_format");
        }
        Integer expected = LENGTHS.get(iban.substring(0, 2));
        if (expected == null) {
            return Optional.of("iban_country_not_supported");
        }
        if (expected != iban.length()) {
            return Optional.of("invalid_iban_length");
        }
        return mod97(iban) == 1 ? Optional.empty() : Optional.of("invalid_iban_checksum");
    }

    /** ISO 7064 MOD 97-10 over the rearranged IBAN (letters as 10..35). */
    static int mod97(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder(rearranged.length() * 2);
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue();
    }

    /** Builds a valid IBAN from a country and BBAN by computing the check digits. */
    public static String of(String country, String bban) {
        String base = country.toUpperCase(Locale.ROOT) + "00" + bban.toUpperCase(Locale.ROOT);
        int check = 98 - mod97(base);
        return country.toUpperCase(Locale.ROOT) + String.format("%02d", check) + bban.toUpperCase(Locale.ROOT);
    }

    public static String country(String iban) {
        return normalise(iban).substring(0, 2);
    }

    /** National bank / branch code embedded in the BBAN (e.g. UK sort code), when known for the country. */
    public static Optional<String> bankCode(String raw) {
        String iban = normalise(raw);
        int[] pos = BANK_CODE.get(iban.substring(0, 2));
        if (pos == null || iban.length() < 4 + pos[1]) {
            return Optional.empty();
        }
        String bban = iban.substring(4);
        return Optional.of(bban.substring(pos[0], pos[1]));
    }

    /** Masked for display: country, check digits and the last four characters. */
    public static String mask(String raw) {
        String iban = normalise(raw);
        if (iban == null || iban.length() < 8) {
            return iban;
        }
        return iban.substring(0, 4) + "*".repeat(iban.length() - 8) + iban.substring(iban.length() - 4);
    }
}
