package io.meridian.cardtokenization.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** HMAC signatures of provider callbacks, step-up OTPs and the opaque push-provisioning payload. */
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
        byte[] given = signatureHex.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given);
    }

    /** A uniformly random numeric code of {@code digits} digits (leading zeros kept). */
    public static String randomDigits(int digits) {
        StringBuilder b = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) {
            b.append(RANDOM.nextInt(10));
        }
        return b.toString();
    }

    /** OTP hash salted with the owning record id, so equal OTPs of different records hash differently. */
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

    public static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** AES-256-GCM (key = SHA-256 of the configured secret); returns base64(IV || ciphertext+tag). */
    public static String seal(String secret, String plaintext) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Inverse of {@link #seal}. */
    public static String open(String secret, String sealed) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            byte[] in = Base64.getDecoder().decode(sealed);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
