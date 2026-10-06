package io.meridian.cardthreeds.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Directory-server signatures, challenge OTPs and CAVV / AAV generation (software stand-in for the ACS HSM). */
public final class Crypto {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {}

    public static byte[] hmac(String key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hmacHex(String key, byte[] data) {
        return HexFormat.of().formatHex(hmac(key, data));
    }

    /** Constant-time check of a hex HMAC-SHA256 signature over {@code data}. */
    public static boolean verifySignature(String secret, byte[] data, String signatureHex) {
        if (signatureHex == null || signatureHex.isBlank()) {
            return false;
        }
        byte[] expected = hmacHex(secret, data).getBytes(StandardCharsets.US_ASCII);
        byte[] given = signatureHex.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given);
    }

    /** A uniformly random numeric OTP (leading zeros kept). */
    public static String newOtp(int digits) {
        StringBuilder b = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) {
            b.append(RANDOM.nextInt(10));
        }
        return b.toString();
    }

    /** OTP hash salted with the authentication id. */
    public static String otpHash(String key, String salt, String otp) {
        return hmacHex(key, (salt + ":" + otp).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean otpMatches(String key, String salt, String otp, String storedHash) {
        if (otp == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(otpHash(key, salt, otp.trim()).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * CAVV / AAV: base64 of the first 20 bytes of HMAC-SHA256(key, acs_transaction_id|card_id|amount|currency),
     * always 28 characters and deterministic for the same authentication.
     */
    public static String cavv(String key, String acsTransactionId, UUID cardId, BigDecimal amount, String currency) {
        String data = acsTransactionId + "|" + cardId + "|"
                + (amount == null ? "" : amount.stripTrailingZeros().toPlainString()) + "|"
                + (currency == null ? "" : currency);
        byte[] mac = hmac(key, data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(Arrays.copyOf(mac, 20));
    }
}
