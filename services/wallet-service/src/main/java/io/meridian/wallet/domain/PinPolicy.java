package io.meridian.wallet.domain;

import io.meridian.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Wallet PIN rules and hashing. A PIN is 4-6 digits, not trivially guessable (all digits equal, ascending or
 * descending runs, the tail of the wallet's mobile number). PINs are stored only as
 * {@code pbkdf2$<iterations>$<salt b64>$<hash b64>} (PBKDF2-HMAC-SHA256, 16-byte random salt, 256-bit key) and
 * compared in constant time. OTPs are numeric and stored as salted SHA-256.
 */
public final class PinPolicy {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "pbkdf2";

    private PinPolicy() {}

    /** Validates a new PIN; throws 422 {@code invalid_pin_format} or {@code weak_pin}. */
    public static void validateNew(String pin, String msisdn) {
        if (pin == null || !pin.matches("\\d{4,6}")) {
            throw ApiException.unprocessable("invalid_pin_format", "PIN must be 4 to 6 digits");
        }
        if (isWeak(pin, msisdn)) {
            throw ApiException.unprocessable("weak_pin",
                    "PIN is too easy to guess (repeated digits, a sequence or part of the mobile number)");
        }
    }

    /** True for repeated digits (1111), ascending/descending runs (1234, 9876) or the tail of the msisdn. */
    public static boolean isWeak(String pin, String msisdn) {
        boolean same = true;
        boolean up = true;
        boolean down = true;
        for (int i = 1; i < pin.length(); i++) {
            int d = pin.charAt(i) - pin.charAt(i - 1);
            same &= d == 0;
            up &= d == 1;
            down &= d == -1;
        }
        if (same || up || down) {
            return true;
        }
        return msisdn != null && msisdn.endsWith(pin);
    }

    public static String hash(String pin, int iterations) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] key = derive(pin, salt, iterations);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return PREFIX + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(key);
    }

    /** Constant-time verification of a PIN against its stored hash; false for malformed hashes. */
    public static boolean verify(String pin, String encoded) {
        if (pin == null || encoded == null) {
            return false;
        }
        String[] p = encoded.split("\\$");
        if (p.length != 4 || !PREFIX.equals(p[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(p[1]);
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] expected = Base64.getDecoder().decode(p[3]);
            return MessageDigest.isEqual(expected, derive(pin, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** A random numeric one-time password of the given length. */
    public static String otp(int digits) {
        StringBuilder sb = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    /** Salted SHA-256 of an OTP: {@code sha256$<salt b64>$<hash b64>}. */
    public static String hashOtp(String otp) {
        byte[] salt = new byte[12];
        RANDOM.nextBytes(salt);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return "sha256$" + b64.encodeToString(salt) + "$" + b64.encodeToString(sha256(salt, otp));
    }

    public static boolean verifyOtp(String otp, String encoded) {
        if (otp == null || encoded == null) {
            return false;
        }
        String[] p = encoded.split("\\$");
        if (p.length != 3) {
            return false;
        }
        byte[] salt = Base64.getDecoder().decode(p[1]);
        return MessageDigest.isEqual(Base64.getDecoder().decode(p[2]), sha256(salt, otp));
    }

    private static byte[] sha256(byte[] salt, String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            return md.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] derive(String pin, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * Outcome of a failed attempt: the new failure count and whether the PIN is now locked.
     *
     * @param attempts    consecutive failures including this one
     * @param locked      the PIN locks with this failure
     * @param remaining   attempts left before the lock (0 when locked)
     */
    public record Failure(int attempts, boolean locked, int remaining) {
        public static Failure after(int previousAttempts, int maxAttempts) {
            int n = previousAttempts + 1;
            boolean locked = n >= maxAttempts;
            return new Failure(n, locked, locked ? 0 : maxAttempts - n);
        }
    }
}
