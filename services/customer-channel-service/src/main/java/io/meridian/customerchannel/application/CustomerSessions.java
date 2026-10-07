package io.meridian.customerchannel.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.customerchannel.application.IdentityProviderSessions.IdpSession;
import io.meridian.customerchannel.config.ChannelProperties;
import io.meridian.events.OutboxWriter;
import io.meridian.web.security.CustomerContext;
import io.meridian.web.security.CustomerContext.CustomerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The customer's sign-in sessions (table customer_session), keyed by the identity provider session id (token
 * {@code sid}; dev mode {@code X-Session-Id}).
 *
 * <p>Why a registry in the channel and the identity provider's admin API together: Keycloak knows which sessions are
 * live and can end them (refresh tokens and SSO stop working), but an access token it already issued stays valid
 * until it expires (15 minutes) and Keycloak cannot say whether a device is new to the customer. The registry
 * records the device of each session (for new-device alerts), refuses every request of a signed-out session at once
 * (401 {@code session_revoked}), and works without the admin client (dev mode, or an identity provider without one).
 * When the sessions client is configured, listing reconciles the registry with Keycloak's live sessions and revoking
 * also ends the session there.
 */
@Component
public class CustomerSessions {
    public static final String TABLE = "customer_session";
    public static final String DEVICE_HEADER = "X-Device-Id";
    private static final Logger log = LoggerFactory.getLogger(CustomerSessions.class);
    private static final String COLUMNS = "tenant_id, created_by, updated_by, customer_id, session_id, subject, "
            + "device_hash, device_label, user_agent, ip_address, last_ip_address, first_seen_at, last_seen_at, "
            + "auth_time, acr, new_device";

    private final ChannelProperties props;
    private final IdentityProviderSessions idp;
    private final JdbcClient jdbc;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final ActionAudit audit;
    private final Clock clock;

    public CustomerSessions(ChannelProperties props, IdentityProviderSessions idp, JdbcClient jdbc, OutboxWriter outbox,
                            TransactionTemplate tx, JsonMapper json, ActionAudit audit, Optional<Clock> clock) {
        this.props = props;
        this.idp = idp;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
        this.audit = audit;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    // ------------------------------------------------------------------ every request

    /**
     * Registers the request's session the first time it is seen (publishing a new-device alert when the customer never
     * used the device before) and refreshes it at most once per touch interval. A revoked or ended session gets 401
     * {@code session_revoked}.
     */
    public void track(HttpServletRequest req, HttpServletResponse res) {
        CustomerPrincipal p = CustomerContext.require();
        if (!props.sessions().enabled() || p.sessionId() == null) {
            return;
        }
        Optional<ObjectNode> row = tx.execute(s -> find(p.customerId(), p.sessionId()));
        if (row.isEmpty()) {
            register(p, req);
            return;
        }
        ObjectNode r = row.get();
        if (!"ACTIVE".equals(r.path("status").asString())) {
            res.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\", "
                    + "error_description=\"This session was signed out\"");
            throw new ApiException(401, "session_revoked", "This session was signed out; sign in again");
        }
        touch(r, p, req);
    }

    private void register(CustomerPrincipal p, HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        String device = req.getHeader(DEVICE_HEADER);
        String deviceHash = sha256(device != null && !device.isBlank() && device.length() <= 128
                ? "device:" + device.trim() : "ua:" + (ua == null ? "" : ua));
        Instant now = clock.instant();
        String ip = ActionAudit.clientIp(req);
        tx.executeWithoutResult(s -> {
            UUID customer = p.customerId();
            boolean any = exists("select exists(select 1 from customer_session where customer_id = ?)", customer);
            boolean known = exists("select exists(select 1 from customer_session where customer_id = ? "
                    + "and device_hash = ?)", customer, deviceHash);
            boolean newDevice = any && !known;
            Optional<ObjectNode> saved = insert(p, deviceHash, label(ua, req.getHeader("X-Channel")), truncate(ua, 255),
                    ip, now, now, newDevice);
            if (saved.isPresent() && newDevice && props.sessions().newDeviceAlerts()) {
                ObjectNode event = saved.get().deepCopy();
                event.put("seen_at", now.toString());
                // template variables of NEW_DEVICE_SIGN_IN are always present
                if (event.path("device_label").isNull() || event.path("device_label").isMissingNode()) {
                    event.put("device_label", "an unknown device");
                }
                if (event.path("ip_address").isNull() || event.path("ip_address").isMissingNode()) {
                    event.put("ip_address", "unknown");
                }
                outbox.publish("channels.customer_session.new_device", TABLE,
                        UUID.fromString(saved.get().path("id").asString()), event);
            }
        });
    }

    private void touch(ObjectNode row, CustomerPrincipal p, HttpServletRequest req) {
        Instant lastSeen = instant(row.path("last_seen_at"));
        Instant auth = p.authTime();
        boolean stronger = props.level(p.acr()) > props.level(row.path("acr").asString(null));
        boolean newAuth = auth != null && !auth.equals(instant(row.path("auth_time")));
        boolean stale = lastSeen == null || lastSeen.plus(props.sessions().touchInterval()).isBefore(clock.instant());
        if (!stale && !stronger && !newAuth) {
            return;
        }
        try {
            tx.executeWithoutResult(s -> jdbc.sql("update customer_session set last_seen_at = ?, "
                            + "last_ip_address = ?::inet, auth_time = coalesce(?, auth_time), acr = ?, updated_by = ? "
                            + "where id = ? and status = 'ACTIVE'")
                    .params(ts(clock.instant()), ActionAudit.clientIp(req), ts(auth),
                            stronger || row.path("acr").isNull() ? p.acr() : row.path("acr").asString(null),
                            p.principalName(), UUID.fromString(row.path("id").asString()))
                    .update());
        } catch (RuntimeException e) {
            log.warn("could not refresh customer session: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ API

    /** The customer's sessions, active first, newest activity first; is_current marks the request's session. */
    public JsonNode list(Integer limit) {
        CustomerPrincipal p = CustomerContext.require();
        reconcile(p);
        int max = limit == null ? 50 : Math.max(1, Math.min(200, limit));
        List<ObjectNode> rows = tx.execute(s -> query("select row_to_json(t)::text from customer_session t "
                + "where t.customer_id = ? order by (t.status = 'ACTIVE') desc, t.last_seen_at desc, t.id desc limit ?",
                p.customerId(), max));
        return page(rows, p);
    }

    /** Signs out one of the customer's sessions (404 when it is not theirs). Idempotent. */
    public JsonNode revoke(UUID id, JsonNode req) {
        CustomerPrincipal p = CustomerContext.require();
        String reason = req.path("reason").asString(null);
        ObjectNode row = tx.execute(s -> query("select row_to_json(t)::text from customer_session t where t.id = ? "
                + "and t.customer_id = ?", id, p.customerId()).stream().findFirst().orElse(null));
        if (row == null) {
            audit.denied("mySessionsRevoke", "SESSION", id, "not_found");
            throw ApiException.notFound("session", id);
        }
        if ("ACTIVE".equals(row.path("status").asString())) {
            boolean ended = idp.endSession(row.path("session_id").asString());
            row = markRevoked(p, List.of(id), reason == null || reason.isBlank() ? "Signed out by the customer"
                    : truncate(reason, 255), ended).stream().findFirst().orElse(row);
            audit.succeeded("mySessionsRevoke", "SESSION", id, null);
        }
        return current(row, p);
    }

    /**
     * Signs the customer out everywhere: every active session, the current one too unless includeCurrent is false.
     * At the identity provider every session of the user is ended (including sessions this channel never saw), or,
     * when the current one is kept, every other one.
     */
    public JsonNode revokeAll(JsonNode req) {
        CustomerPrincipal p = CustomerContext.require();
        boolean includeCurrent = !req.path("include_current").isBoolean() || req.path("include_current").asBoolean();
        String keep = includeCurrent ? null : p.sessionId();
        boolean ended = false;
        if (p.subject() != null && idp.configured()) {
            if (includeCurrent) {
                ended = idp.endAll(p.subject());
            } else {
                ended = idp.sessions(p.subject()).map(list -> list.stream().filter(s -> !s.id().equals(keep))
                        .allMatch(s -> idp.endSession(s.id()))).orElse(false);
            }
        }
        List<UUID> ids = tx.execute(s -> query("select row_to_json(t)::text from customer_session t "
                + "where t.customer_id = ? and t.status = 'ACTIVE'", p.customerId()).stream()
                .filter(r -> keep == null || !keep.equals(r.path("session_id").asString()))
                .map(r -> UUID.fromString(r.path("id").asString())).toList());
        List<ObjectNode> revoked = markRevoked(p, ids, "SIGN_OUT_EVERYWHERE", ended);
        audit.succeeded("mySessionsRevokeAll", "SESSION", null, null);
        return page(revoked, p);
    }

    // ------------------------------------------------------------------ internals

    private List<ObjectNode> markRevoked(CustomerPrincipal p, List<UUID> ids, String reason, boolean idpEnded) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return tx.execute(s -> {
            List<ObjectNode> out = new ArrayList<>();
            for (UUID id : ids) {
                jdbc.sql("update customer_session set status = 'REVOKED', revoked_at = ?, revoked_reason = ?, "
                                + "idp_session_ended = ?, updated_by = ? where id = ? and customer_id = ? "
                                + "and status = 'ACTIVE' returning row_to_json(customer_session)::text")
                        .params(ts(clock.instant()), reason, idpEnded, p.principalName(), id, p.customerId())
                        .query(String.class).optional().map(t -> (ObjectNode) json.readTree(t)).ifPresent(r -> {
                            out.add(r);
                            outbox.publish("channels.customer_session.revoked", TABLE, id, r);
                        });
            }
            return out;
        });
    }

    /** Registry vs the identity provider: unknown live sessions are added, vanished ones marked ENDED. */
    private void reconcile(CustomerPrincipal p) {
        if (p.subject() == null || !idp.configured()) {
            return;
        }
        Optional<List<IdpSession>> live = idp.sessions(p.subject());
        if (live.isEmpty()) {
            return;
        }
        Set<String> liveIds = live.get().stream().map(IdpSession::id).collect(Collectors.toSet());
        try {
            tx.executeWithoutResult(s -> {
                for (ObjectNode r : query("select row_to_json(t)::text from customer_session t where "
                        + "t.customer_id = ? and t.status = 'ACTIVE' and t.subject = ?", p.customerId(), p.subject())) {
                    if (!liveIds.contains(r.path("session_id").asString())) {
                        jdbc.sql("update customer_session set status = 'ENDED', idp_session_ended = true, "
                                        + "updated_by = ? where id = ? and status = 'ACTIVE'")
                                .params(p.principalName(), UUID.fromString(r.path("id").asString())).update();
                    }
                }
                for (IdpSession k : live.get()) {
                    Instant start = k.start() == null ? clock.instant() : k.start();
                    insert(new CustomerPrincipal(p.customerId(), start, null, List.of(), k.id(), p.subject()),
                            null, null, null, validIp(k.ipAddress()), start,
                            k.lastAccess() == null ? start : k.lastAccess(), false);
                }
            });
        } catch (RuntimeException e) {
            log.warn("could not reconcile customer sessions: {}", e.getMessage());
        }
    }

    private Optional<ObjectNode> insert(CustomerPrincipal p, String deviceHash, String label, String ua, String ip,
                                        Instant first, Instant last, boolean newDevice) {
        return jdbc.sql("insert into customer_session (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::inet, "
                        + "?::inet, ?, ?, ?, ?, ?) on conflict (customer_id, session_id) do nothing "
                        + "returning row_to_json(customer_session)::text")
                .params(TenantContext.require(), p.principalName(), p.principalName(), p.customerId(), p.sessionId(),
                        p.subject(), deviceHash, label, ua, ip, ip, ts(first), ts(last), ts(p.authTime()),
                        truncate(p.acr(), 16), newDevice)
                .query(String.class).optional().map(t -> (ObjectNode) json.readTree(t));
    }

    private Optional<ObjectNode> find(UUID customer, String sessionId) {
        return query("select row_to_json(t)::text from customer_session t where t.customer_id = ? "
                + "and t.session_id = ?", customer, sessionId).stream().findFirst();
    }

    private List<ObjectNode> query(String sql, Object... args) {
        List<ObjectNode> out = new ArrayList<>();
        for (String s : jdbc.sql(sql).params(java.util.Arrays.asList(args)).query(String.class).list()) {
            out.add((ObjectNode) json.readTree(s));
        }
        return out;
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.sql(sql).params(java.util.Arrays.asList(args)).query(Boolean.class).single());
    }

    private ObjectNode page(List<ObjectNode> rows, CustomerPrincipal p) {
        ObjectNode out = json.createObjectNode();
        ArrayNode data = out.putArray("data");
        rows.forEach(r -> data.add(current(r, p)));
        out.put("has_more", false);
        return out;
    }

    private static ObjectNode current(ObjectNode row, CustomerPrincipal p) {
        row.put("is_current", p.sessionId() != null && p.sessionId().equals(row.path("session_id").asString(null)));
        return row;
    }

    /** "Chrome on Windows", "Meridian app on Android", ... from the user agent (null when unknown). */
    static String label(String ua, String channel) {
        if (ua == null || ua.isBlank()) {
            return "MOBILE".equalsIgnoreCase(channel) ? "Mobile app" : null;
        }
        String browser = ua.contains("Edg/") ? "Edge" : ua.contains("OPR/") ? "Opera"
                : ua.contains("Firefox/") ? "Firefox" : ua.contains("Chrome/") ? "Chrome"
                : ua.contains("Safari/") ? "Safari" : "MOBILE".equalsIgnoreCase(channel) ? "Mobile app" : "Browser";
        String os = ua.contains("Android") ? "Android" : ua.contains("iPhone") || ua.contains("iPad") ? "iOS"
                : ua.contains("Windows") ? "Windows" : ua.contains("Mac OS") ? "macOS"
                : ua.contains("CrOS") ? "ChromeOS" : ua.contains("Linux") ? "Linux" : null;
        return os == null ? browser : browser + " on " + os;
    }

    private static String validIp(String ip) {
        return ip != null && ip.matches("[0-9a-fA-F:.]{2,45}") ? ip : null;
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.substring(0, Math.min(max, s.length()));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(n.asString()).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
