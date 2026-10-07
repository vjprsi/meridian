package io.meridian.audit.infrastructure;

import io.meridian.audit.application.AuditRecorder;
import io.meridian.audit.application.AuditRecorder.NewAuditEvent;
import io.meridian.common.tenancy.TenantContext;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GAP-06: copies Keycloak user and admin events into the audit trail by polling the admin events API with the
 * read-only {@code meridian-audit-events} client (realm-management {@code view-events}; nothing else).
 *
 * <p>Why polling and not an event-listener SPI: Keycloak 26 ships no Kafka / HTTP event listener, and a custom provider
 * jar would have to be built, versioned and installed into every Keycloak image. The realm stores the events itself
 * ({@code eventsEnabled}, {@code adminEventsEnabled}, 90-day expiry), so polling is lossless across audit-service
 * restarts and Keycloak outages shorter than the expiry; each event is recorded once ({@code source_event_id}
 * {@code kc:<id>} / {@code kc-admin:<id>}, de-duplicated on insert), so overlapping windows are harmless.
 *
 * <p>Events are recorded in the tenant {@code meridian.audit.keycloak.tenant} (the platform-operator tenant: the IdP is
 * platform infrastructure, and the reader has no access to user attributes). The window starts
 * {@code meridian.audit.keycloak.lookback} back at start-up and then follows the newest event seen (minus
 * {@code overlap}); Keycloak returns newest first, so each poll pages back until it passes the window start.
 * Off unless {@code meridian.audit.keycloak.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "meridian.audit.keycloak.enabled", havingValue = "true")
public class KeycloakEventsPoller {
    private static final Logger log = LoggerFactory.getLogger(KeycloakEventsPoller.class);

    /** Where mapped events go (the audit recorder in the service; a list in tests). */
    public interface Sink {
        boolean record(UUID tenant, NewAuditEvent event) throws Exception;
    }

    private final JsonMapper json;
    private final KeycloakEventMapper mapper;
    private final Sink sink;
    private final String base;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final UUID tenant;
    private final int pageSize;
    private final Duration overlap;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile long userCursor;
    private volatile long adminCursor;
    private volatile String token;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    @Autowired
    public KeycloakEventsPoller(AuditRecorder recorder, JsonMapper json, Environment env) {
        this(json, env, (tenant, event) -> TenantContext.callAs(tenant, "audit-service",
                () -> recorder.recordOnce(event).isPresent()));
    }

    public KeycloakEventsPoller(JsonMapper json, Environment env, Sink sink) {
        this.json = json;
        this.mapper = new KeycloakEventMapper(json);
        this.sink = sink;
        this.base = env.getProperty("meridian.audit.keycloak.base-url", "http://localhost:15180").replaceAll("/+$", "");
        this.realm = env.getProperty("meridian.audit.keycloak.realm", "meridian");
        this.clientId = env.getProperty("meridian.audit.keycloak.client-id", "meridian-audit-events");
        this.clientSecret = env.getProperty("meridian.audit.keycloak.client-secret");
        this.tenant = UUID.fromString(env.getRequiredProperty("meridian.audit.keycloak.tenant"));
        this.pageSize = env.getProperty("meridian.audit.keycloak.page-size", Integer.class, 200);
        this.overlap = duration(env, "meridian.audit.keycloak.overlap", "2m");
        long start = Instant.now().minus(duration(env, "meridian.audit.keycloak.lookback", "2d")).toEpochMilli();
        this.userCursor = start;
        this.adminCursor = start;
    }

    @Scheduled(fixedDelayString = "${meridian.audit.keycloak.poll-interval:60s}",
            initialDelayString = "${meridian.audit.keycloak.initial-delay:30s}")
    public void poll() {
        try {
            int users = pollOnce();
            if (users > 0) {
                log.debug("Recorded {} Keycloak event(s)", users);
            }
        } catch (RuntimeException e) {
            log.warn("Keycloak events poll failed (will retry): {}", e.toString());
        }
    }

    /** One poll of user and admin events; returns the number of newly recorded events. */
    public int pollOnce() {
        int recorded = 0;
        long[] newest = {userCursor};
        recorded += drain("events", userCursor, newest, mapper::userEvent);
        userCursor = newest[0];
        long[] newestAdmin = {adminCursor};
        recorded += drain("admin-events", adminCursor, newestAdmin, mapper::adminEvent);
        adminCursor = newestAdmin[0];
        return recorded;
    }

    private int drain(String kind, long cursor, long[] newest, Function<JsonNode, NewAuditEvent> map) {
        long from = cursor - overlap.toMillis();
        String day = Instant.ofEpochMilli(from).atZone(ZoneOffset.UTC).toLocalDate().toString();
        List<JsonNode> window = new ArrayList<>();
        for (int first = 0; first < 100_000; first += pageSize) {
            JsonNode page = get("/admin/realms/" + enc(realm) + "/" + kind + "?dateFrom=" + day + "&first=" + first
                    + "&max=" + pageSize);
            if (!page.isArray() || page.isEmpty()) {
                break;
            }
            boolean older = false;
            for (JsonNode e : page) {
                long t = e.path("time").asLong();
                if (t < from) {
                    older = true;
                } else {
                    window.add(e);
                }
            }
            if (older || page.size() < pageSize) {
                break;
            }
        }
        int recorded = 0;
        for (int i = window.size() - 1; i >= 0; i--) {         // oldest first: the audit chain follows event time
            JsonNode e = window.get(i);
            try {
                if (sink.record(tenant, map.apply(e))) {
                    recorded++;
                }
                newest[0] = Math.max(newest[0], e.path("time").asLong());
            } catch (Exception ex) {
                log.warn("Keycloak {} event {} not recorded: {}", kind, e.path("id").asString(""), ex.toString());
                return recorded;           // keep the cursor before the failed event: retried next poll
            }
        }
        return recorded;
    }

    private JsonNode get(String path) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token()).header("Accept", "application/json").GET().build();
        HttpResponse<String> r = send(req);
        if (r.statusCode() == 401) {
            tokenExpiry = Instant.EPOCH;
        }
        if (r.statusCode() != 200) {
            throw new IllegalStateException("Keycloak " + path.replaceAll("\\?.*", "") + " returned " + r.statusCode());
        }
        return json.readTree(r.body());
    }

    private String token() {
        if (token != null && Instant.now().isBefore(tokenExpiry.minusSeconds(30))) {
            return token;
        }
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/realms/" + enc(realm) + "/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build();
        HttpResponse<String> r = send(req);
        if (r.statusCode() != 200) {
            throw new IllegalStateException("Keycloak token endpoint returned " + r.statusCode());
        }
        JsonNode body = json.readTree(r.body());
        token = body.get("access_token").asString();
        tokenExpiry = Instant.now().plusSeconds(body.path("expires_in").asLong(60));
        return token;
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Keycloak unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static Duration duration(Environment env, String key, String fallback) {
        return DurationStyle.detectAndParse(env.getProperty(key, fallback).trim());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
