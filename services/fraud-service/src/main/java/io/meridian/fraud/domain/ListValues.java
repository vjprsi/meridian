package io.meridian.fraud.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Fraud list values are stored only as SHA-256 of a normalised form (plus a masked value for display), so a list
 * of card numbers or national ids never holds the clear value.
 */
public final class ListValues {
    private ListValues() {}

    /** Canonical form per entity type, so "AB12 34" and "ab1234" hash identically where that is meaningful. */
    public static String normalise(String entityType, String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return switch (entityType) {
            case "EMAIL" -> v.toLowerCase(Locale.ROOT);
            case "PHONE" -> v.replaceAll("[^0-9+]", "");
            case "CARD", "CARD_BIN", "ACCOUNT", "BENEFICIARY_ACCOUNT", "NATIONAL_ID" ->
                    v.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
            case "COUNTRY" -> v.toUpperCase(Locale.ROOT);
            case "IP_ADDRESS", "DEVICE", "MERCHANT" -> v.toLowerCase(Locale.ROOT);
            default -> v;
        };
    }

    public static String hash(String entityType, String value) {
        return sha256(entityType + ":" + normalise(entityType, value));
    }

    /** Keeps the last 4 characters (first 2 + domain for e-mails); countries and BINs are not sensitive. */
    public static String mask(String entityType, String value) {
        String v = normalise(entityType, value);
        if (v == null) {
            return null;
        }
        if ("COUNTRY".equals(entityType) || "CARD_BIN".equals(entityType)) {
            return v;
        }
        if ("EMAIL".equals(entityType) && v.contains("@")) {
            int at = v.indexOf('@');
            return v.substring(0, Math.min(2, at)) + "***" + v.substring(at);
        }
        if (v.length() <= 4) {
            return "*".repeat(v.length());
        }
        String tail = v.substring(v.length() - 4);
        String masked = "*".repeat(Math.min(8, v.length() - 4)) + tail;
        return masked.length() > 64 ? masked.substring(masked.length() - 64) : masked;
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
