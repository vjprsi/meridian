package io.meridian.cardauthorization.domain;

import java.security.SecureRandom;
import java.util.Map;

/** ISO 8583 DE39 response codes for internal decline reasons, and approval code (DE38) generation. */
public final class Iso {
    public static final String APPROVED = "00";
    public static final String PARTIAL = "10";
    public static final String ISSUER_UNAVAILABLE = "91";

    private static final Map<String, String> CODES = Map.ofEntries(
            Map.entry("INSUFFICIENT_FUNDS", "51"),
            Map.entry("CARD_INACTIVE", "78"),
            Map.entry("CARD_FROZEN", "57"),
            Map.entry("CARD_EXPIRED", "54"),
            Map.entry("INVALID_PIN", "55"),
            Map.entry("PIN_TRIES_EXCEEDED", "75"),
            Map.entry("CONTROL_MCC", "57"),
            Map.entry("CONTROL_MERCHANT", "57"),
            Map.entry("CONTROL_COUNTRY", "57"),
            Map.entry("CONTROL_CHANNEL", "57"),
            Map.entry("CONTROL_TIME", "57"),
            Map.entry("CONTROL_CURRENCY", "57"),
            Map.entry("CONTROL_AMOUNT", "61"),
            Map.entry("LIMIT_EXCEEDED", "61"),
            Map.entry("VELOCITY_EXCEEDED", "65"),
            Map.entry("FRAUD_SUSPECTED", "59"),
            Map.entry("JIT_DECLINED", "51"),
            Map.entry("JIT_TIMEOUT", "91"),
            Map.entry("INVALID_CVV", "N7"),
            Map.entry("INVALID_EXPIRY", "54"),
            Map.entry("THREEDS_FAILED", "05"),
            Map.entry("SYSTEM_ERROR", "96"),
            Map.entry("OTHER", "05"),
            Map.entry("CARD_LOST", "41"),
            Map.entry("CARD_STOLEN", "43"),
            Map.entry("CARD_BLOCKED", "62"),
            Map.entry("PROGRAM_SUSPENDED", "62"),
            Map.entry("RULE_DECLINED", "05"),
            Map.entry("ORIGINAL_NOT_FOUND", "25"));

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALNUM = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789".toCharArray();

    private Iso() {}

    /** DE39 for a decline reason ("05 do not honour" for anything unknown). */
    public static String code(String declineReason) {
        return CODES.getOrDefault(declineReason, "05");
    }

    /** Six-character alphanumeric approval code. */
    public static String authCode() {
        char[] c = new char[6];
        for (int i = 0; i < c.length; i++) {
            c[i] = ALNUM[RANDOM.nextInt(ALNUM.length)];
        }
        return new String(c);
    }
}
