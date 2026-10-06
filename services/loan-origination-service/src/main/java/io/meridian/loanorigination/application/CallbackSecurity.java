package io.meridian.loanorigination.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.loanorigination.config.OriginationProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Public provider callbacks carry neither a token nor a tenant: they are authenticated by an HMAC-SHA256 signature
 * over the raw body ({@value #SIGNATURE_HEADER}: {@code sha256=<hex>}) and routed to the owning tenant through the
 * platform-global {@code lending_callback_route} table (no row-level security), written when the envelope or the
 * asynchronous bureau request is created.
 */
@Component
public class CallbackSecurity {
    public static final String SIGNATURE_HEADER = "X-Meridian-Signature";

    public record Route(UUID tenantId, UUID entityId) {}

    private final JdbcClient jdbc;
    private final OriginationProperties props;

    public CallbackSecurity(JdbcClient jdbc, OriginationProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public String sign(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.callbackSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public void verify(byte[] rawBody, String header) {
        if (header == null || rawBody == null || rawBody.length == 0
                || !MessageDigest.isEqual(sign(rawBody).getBytes(StandardCharsets.UTF_8),
                        header.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(401, "invalid_signature", "Missing or invalid " + SIGNATURE_HEADER + " header");
        }
    }

    /** Records where callbacks for {@code reference} belong (inside the caller's transaction). */
    public void register(String kind, String provider, String reference, UUID entityId) {
        jdbc.sql("""
                insert into lending_callback_route (kind, provider, provider_reference, entity_id, owner_tenant_id,
                                                    created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?) on conflict (kind, provider, provider_reference) do nothing""")
                .params(kind, provider.toUpperCase(Locale.ROOT), reference, entityId, TenantContext.require(),
                        TenantContext.principal(), TenantContext.principal())
                .update();
    }

    public Route route(String kind, String provider, String reference) {
        Optional<Route> r = jdbc.sql("""
                select owner_tenant_id, entity_id from lending_callback_route
                where kind = ? and provider = ? and provider_reference = ?""")
                .params(kind, provider.toUpperCase(Locale.ROOT), reference)
                .query((rs, i) -> new Route(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .optional();
        return r.orElseThrow(() -> ApiException.notFound("E_SIGNATURE".equals(kind) ? "e-signature envelope" : "bureau report",
                reference));
    }
}
