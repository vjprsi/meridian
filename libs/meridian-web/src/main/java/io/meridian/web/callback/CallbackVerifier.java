package io.meridian.web.callback;

import io.meridian.common.error.ApiException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SEC-17: authenticity, freshness and single use of public provider callbacks.
 *
 * <p>Signature scheme v2 (every provider callback): the provider sends
 * <pre>
 *   X-Callback-Timestamp: &lt;unix seconds&gt;
 *   X-Callback-Nonce:     &lt;unique event id, 8..128 chars of [A-Za-z0-9._:-]&gt;
 *   &lt;service signature header&gt;: sign( "&lt;timestamp&gt;.&lt;nonce&gt;.&lt;tenant&gt;." + body )
 * </pre>
 * where {@code sign} is the service's existing HMAC (same secret, same encoding as before) and {@code tenant} is the
 * tenant the callback is addressed to ({@code ?tenant_id} of the callback URL, or empty when the service resolves the
 * tenant from the referenced entity). The timestamp must be within {@code meridian.callbacks.max-skew} (default 5
 * minutes) and each nonce is accepted once per provider ({@code callback_nonce} table, kept for twice the skew).
 *
 * <p>Deprecation: callbacks without timestamp/nonce are verified the old way (signature over the body only) while
 * {@code meridian.callbacks.accept-legacy-signatures} is true (default during the migration); set it to false once
 * every provider sends v2. Legacy acceptances are logged.
 */
public final class CallbackVerifier {
    public static final String TIMESTAMP_HEADER = "X-Callback-Timestamp";
    public static final String NONCE_HEADER = "X-Callback-Nonce";
    private static final Logger log = LoggerFactory.getLogger(CallbackVerifier.class);

    private final NonceStore nonces;
    private final Duration maxSkew;
    private final boolean acceptLegacy;
    private final Clock clock;

    public CallbackVerifier(NonceStore nonces, Duration maxSkew, boolean acceptLegacy, Clock clock) {
        this.nonces = nonces;
        this.maxSkew = maxSkew;
        this.acceptLegacy = acceptLegacy;
        this.clock = clock;
    }

    /** The v2 headers of a callback request ({@link #TIMESTAMP_HEADER}, {@link #NONCE_HEADER}); either may be null. */
    public record Headers(String timestamp, String nonce) {
        public static final Headers NONE = new Headers(null, null);

        public static Headers of(jakarta.servlet.http.HttpServletRequest req) {
            return req == null ? NONE : new Headers(req.getHeader(TIMESTAMP_HEADER), req.getHeader(NONCE_HEADER));
        }

        /** Headers of the request being handled on this thread (none outside a request). */
        public static Headers current() {
            return org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                    instanceof org.springframework.web.context.request.ServletRequestAttributes a ? of(a.getRequest()) : NONE;
        }
    }

    /** {@link #verify(String, String, byte[], String, String, String, Function)} with the request's v2 headers. */
    public void verify(String provider, String tenant, byte[] body, String signature, Headers headers,
                       Function<byte[], String> sign) {
        Headers h = headers == null ? Headers.NONE : headers;
        verify(provider, tenant, body, signature, h.timestamp(), h.nonce(), sign);
    }

    public boolean acceptsLegacy() {
        return acceptLegacy;
    }

    /** The bytes a v2 signature covers. */
    public static byte[] v2Payload(String timestamp, String nonce, String tenant, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes((timestamp + "." + nonce + "." + (tenant == null ? "" : tenant.toLowerCase(Locale.ROOT)) + ".")
                .getBytes(StandardCharsets.UTF_8));
        out.writeBytes(body == null ? new byte[0] : body);
        return out.toByteArray();
    }

    /**
     * Verifies a callback or throws: 401 {@code invalid_signature} (missing / wrong signature, or legacy refused),
     * 401 {@code stale_callback} (timestamp outside the window), 409 {@code callback_replayed} (nonce seen before).
     *
     * @param provider  replay namespace (e.g. {@code routing:<rail id>}, {@code kyc:<provider code>})
     * @param tenant    tenant the callback addresses (bound into the v2 signature), may be null
     * @param body      what the service signs (raw body, or its canonical form)
     * @param signature value of the service's signature header
     * @param sign      the service's signing function (secret + encoding), compared as strings
     */
    public void verify(String provider, String tenant, byte[] body, String signature, String timestamp, String nonce,
                       Function<byte[], String> sign) {
        if (signature == null || signature.isBlank()) {
            throw new ApiException(401, "invalid_signature", "The callback signature is missing");
        }
        boolean v2 = !blank(timestamp) || !blank(nonce);
        if (!v2) {
            if (!acceptLegacy) {
                throw new ApiException(401, "invalid_signature", "Callbacks must be signed with "
                        + TIMESTAMP_HEADER + " and " + NONCE_HEADER + " (signature v2)");
            }
            if (!matches(sign.apply(body == null ? new byte[0] : body), signature)) {
                throw new ApiException(401, "invalid_signature", "The callback signature does not match");
            }
            log.warn("Accepted a legacy (body-only) callback signature from {}; legacy signatures are deprecated", provider);
            return;
        }
        if (blank(timestamp) || blank(nonce) || !nonce.trim().matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ApiException(401, "invalid_signature", "Both " + TIMESTAMP_HEADER + " and a valid "
                    + NONCE_HEADER + " are required");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(401, "invalid_signature", TIMESTAMP_HEADER + " must be unix seconds");
        }
        if (!matches(sign.apply(v2Payload(timestamp.trim(), nonce.trim(), tenant, body)), signature)) {
            throw new ApiException(401, "invalid_signature", "The callback signature does not match");
        }
        Instant now = clock.instant();
        if (Math.abs(now.getEpochSecond() - ts) > maxSkew.toSeconds()) {
            throw new ApiException(401, "stale_callback", "The callback timestamp is outside the accepted window");
        }
        if (!nonces.firstUse(provider, nonce.trim(), now, maxSkew.multipliedBy(2))) {
            throw new ApiException(409, "callback_replayed", "This callback was already received");
        }
    }

    private static boolean matches(String expected, String given) {
        if (expected == null || given == null) {
            return false;
        }
        String e = strip(expected);
        String g = strip(given);
        return MessageDigest.isEqual(e.getBytes(StandardCharsets.UTF_8), g.getBytes(StandardCharsets.UTF_8))
                || MessageDigest.isEqual(e.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                        g.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)) && isHex(e);
    }

    private static String strip(String s) {
        return s.trim();
    }

    private static boolean isHex(String s) {
        String v = s.startsWith("sha256=") ? s.substring(7) : s;
        return v.matches("[0-9a-fA-F]+");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
