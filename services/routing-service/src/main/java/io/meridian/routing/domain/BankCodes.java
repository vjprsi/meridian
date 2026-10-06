package io.meridian.routing.domain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** IBAN checks and national bank-code extraction, BIC format, HMAC callback signatures and SHA-256 hashing. */
public final class BankCodes {
    /** Country -> {bankCodeStart, bankCodeEnd (within BBAN), clearing system}. */
    private static final Map<String, Object[]> BANK_CODE = Map.ofEntries(
            Map.entry("GB", new Object[] {4, 10, "GBDSC"}),
            Map.entry("IE", new Object[] {4, 10, "IENCC"}),
            Map.entry("DE", new Object[] {0, 8, "DEBLZ"}),
            Map.entry("AT", new Object[] {0, 5, "ATBLZ"}),
            Map.entry("CH", new Object[] {0, 5, "CHBCC"}),
            Map.entry("NL", new Object[] {0, 4, null}),
            Map.entry("BE", new Object[] {0, 3, null}),
            Map.entry("FR", new Object[] {0, 5, null}),
            Map.entry("ES", new Object[] {0, 4, "ESNCC"}),
            Map.entry("IT", new Object[] {1, 6, "ITNCC"}));

    private static final Map<String, Integer> LENGTHS = Map.ofEntries(
            Map.entry("AT", 20), Map.entry("BE", 16), Map.entry("CH", 21), Map.entry("DE", 22), Map.entry("DK", 18),
            Map.entry("ES", 24), Map.entry("FI", 18), Map.entry("FR", 27), Map.entry("GB", 22), Map.entry("IE", 22),
            Map.entry("IT", 27), Map.entry("LU", 20), Map.entry("NL", 18), Map.entry("NO", 15), Map.entry("PL", 28),
            Map.entry("PT", 25), Map.entry("SE", 24), Map.entry("BR", 29), Map.entry("SA", 24), Map.entry("AE", 23));

    public record IbanBank(String country, String bankCode, String clearingSystem) {}

    private BankCodes() {}

    public static String normaliseIban(String raw) {
        return raw == null ? null : raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /** Empty when valid; otherwise a stable error code. */
    public static Optional<String> ibanError(String raw) {
        String iban = normaliseIban(raw);
        if (iban == null || !iban.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")) {
            return Optional.of("invalid_iban_format");
        }
        Integer len = LENGTHS.get(iban.substring(0, 2));
        if (len != null && len != iban.length()) {
            return Optional.of("invalid_iban_length");
        }
        String r = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (char c : r.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1 ? Optional.empty()
                : Optional.of("invalid_iban_checksum");
    }

    /** Country and national bank code (e.g. GB sort code, DE Bankleitzahl) of a valid IBAN. */
    public static IbanBank bankOf(String raw) {
        String iban = normaliseIban(raw);
        String country = iban.substring(0, 2);
        Object[] spec = BANK_CODE.get(country);
        if (spec == null) {
            return new IbanBank(country, null, null);
        }
        String bban = iban.substring(4);
        int s = (Integer) spec[0];
        int e = (Integer) spec[1];
        return new IbanBank(country, bban.length() >= e ? bban.substring(s, e) : null, (String) spec[2]);
    }

    public static boolean isBic(String raw) {
        return raw != null && raw.trim().toUpperCase(Locale.ROOT).matches("[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?");
    }

    /** "sha256=<hex>" HMAC of the payload. */
    public static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison of a presented signature against the expected one. */
    public static boolean verify(String secret, String payload, String presented) {
        if (secret == null || presented == null || payload == null) {
            return false;
        }
        String expected = sign(secret, payload);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
