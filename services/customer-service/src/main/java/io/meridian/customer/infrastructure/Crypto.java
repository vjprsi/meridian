package io.meridian.customer.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cryptography for customer secrets, JDK only. Secrets (PINs, OTPs) are stored as salted PBKDF2-HMAC-SHA256
 * hashes; identifiers get a keyed HMAC for exact-match search and duplicate detection without decrypting.
 */
@Component
public class Crypto {
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int OTP_ITERATIONS = 10_000;
    private final SecureRandom random = new SecureRandom();
    private final byte[] key;

    public Crypto(@Value("${meridian.customer.secret-key}") String secretKey) {
        if (secretKey == null || secretKey.length() < 16) {
            throw new IllegalStateException("meridian.customer.secret-key must be at least 16 characters");
        }
        this.key = secretKey.getBytes(StandardCharsets.UTF_8);
    }

    /** Keyed hash of a normalised identifier value (hex, 64 chars). */
    public String identifierHash(String normalised) {
        return HexFormat.of().formatHex(hmac(("identifier:" + normalised).getBytes(StandardCharsets.UTF_8)));
    }

    public byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A uniformly random numeric code of the given length (e.g. 6-digit OTP). */
    public String numericCode(int digits) {
        StringBuilder sb = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    public byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    public String hashSecret(String secret) {
        return pbkdf2(secret, PBKDF2_ITERATIONS);
    }

    /** OTPs are short-lived and attempt-limited, so fewer iterations; still salted and peppered with the key. */
    public String hashOtp(String code) {
        return pbkdf2(HexFormat.of().formatHex(hmac(("otp:" + code).getBytes(StandardCharsets.UTF_8))), OTP_ITERATIONS);
    }

    public boolean verifyOtp(String code, String stored) {
        return verifySecret(HexFormat.of().formatHex(hmac(("otp:" + code).getBytes(StandardCharsets.UTF_8))), stored);
    }

    private String pbkdf2(String secret, int iterations) {
        byte[] salt = randomBytes(16);
        byte[] hash = derive(secret.toCharArray(), salt, iterations);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return "pbkdf2-sha256$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    /** Constant-time verification of a secret against its stored PBKDF2 hash. */
    public boolean verifySecret(String secret, String stored) {
        if (secret == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) {
            return false;
        }
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        byte[] actual = derive(secret.toCharArray(), salt, Integer.parseInt(parts[1]));
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] derive(char[] secret, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(secret, salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Stateless, tamper-proof challenge bound to a context (tenant, customer, device): nonce(16) | expiry(8) |
     * mac(16) as base64url. Verification recomputes the MAC and checks the expiry.
     */
    public String challenge(String context, long expiresEpochSecond) {
        byte[] nonce = randomBytes(16);
        ByteBuffer b = ByteBuffer.allocate(40);
        b.put(nonce).putLong(expiresEpochSecond).put(challengeMac(context, nonce, expiresEpochSecond));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b.array());
    }

    /** True when {@code challenge} was issued by {@link #challenge} for the same context and has not expired. */
    public boolean verifyChallenge(String challenge, String context, long nowEpochSecond) {
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(challenge);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (raw.length != 40) {
            return false;
        }
        ByteBuffer b = ByteBuffer.wrap(raw);
        byte[] nonce = new byte[16];
        b.get(nonce);
        long expires = b.getLong();
        byte[] mac = new byte[16];
        b.get(mac);
        return expires >= nowEpochSecond && MessageDigest.isEqual(mac, challengeMac(context, nonce, expires));
    }

    private byte[] challengeMac(String context, byte[] nonce, long expires) {
        ByteBuffer b = ByteBuffer.allocate(context.length() * 4 + 24);
        b.put(context.getBytes(StandardCharsets.UTF_8)).put(nonce).putLong(expires);
        byte[] full = hmac(java.util.Arrays.copyOf(b.array(), b.position()));
        return java.util.Arrays.copyOf(full, 16);
    }

    /**
     * Verifies a signature made with the device-bound key over {@code message}. The key is an X.509
     * SubjectPublicKeyInfo (base64, PEM armour tolerated) of type EC (P-256), RSA or Ed25519.
     */
    public boolean verifySignature(String publicKey, String message, String signatureB64) {
        try {
            String clean = publicKey.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(clean);
            PublicKey pk = null;
            String algorithm = null;
            for (String[] candidate : new String[][] {{"EC", "SHA256withECDSA"}, {"RSA", "SHA256withRSA"}, {"Ed25519", "Ed25519"}}) {
                try {
                    pk = KeyFactory.getInstance(candidate[0]).generatePublic(new X509EncodedKeySpec(der));
                    algorithm = candidate[1];
                    break;
                } catch (GeneralSecurityException ignored) {
                    // try the next key type
                }
            }
            if (pk == null) {
                return false;
            }
            Signature s = Signature.getInstance(algorithm);
            s.initVerify(pk);
            s.update(message.getBytes(StandardCharsets.UTF_8));
            return s.verify(decodeBase64(signatureB64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean isPublicKey(String publicKey) {
        try {
            String clean = publicKey.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(clean);
            for (String alg : new String[] {"EC", "RSA", "Ed25519"}) {
                try {
                    KeyFactory.getInstance(alg).generatePublic(new X509EncodedKeySpec(der));
                    return true;
                } catch (GeneralSecurityException ignored) {
                    // next
                }
            }
            return false;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static byte[] decodeBase64(String s) {
        String t = s.trim();
        return t.contains("-") || t.contains("_") ? Base64.getUrlDecoder().decode(t.replace("=", ""))
                : Base64.getDecoder().decode(t);
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
