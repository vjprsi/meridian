package io.meridian.router;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test estate for the router: fake cell gateways (each answers with its own name and records the headers it got), a
 * fake tenant registry (routing views, cell list, JWKS of a test IdP) whose content tests change at will, and a
 * token minter.
 */
final class FakeEstate implements AutoCloseable {
    static final String ISSUER = "https://idp.test/realms/meridian";
    static final String AUDIENCE = "meridian-api";

    record Cell(UUID id, String code, HttpServer server) {
        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }
    }

    final Map<String, Cell> cells = new ConcurrentHashMap<>();
    final Map<String, String> cellStatus = new ConcurrentHashMap<>();
    /** tenant -> {primary cell code, standby cell code or null, traffic mode}. */
    final Map<UUID, String[]> placements = new ConcurrentHashMap<>();
    final Map<String, Map<String, List<String>>> lastHeaders = new ConcurrentHashMap<>();
    final AtomicInteger routingLookups = new AtomicInteger();
    final Map<String, String> registryAuth = new ConcurrentHashMap<>();
    final HttpServer registry;
    final RSAKey key;
    final RSAKey otherKey;

    FakeEstate(String... cellCodes) throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        for (String code : cellCodes) {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                Map<String, List<String>> seen = new ConcurrentHashMap<>();
                ex.getRequestHeaders().forEach((k, v) -> seen.put(k.toLowerCase(), new ArrayList<>(v)));
                lastHeaders.put(code, seen);
                ex.getRequestBody().readAllBytes();
                reply(ex, 200, "{\"cell\":\"" + code + "\",\"path\":\"" + ex.getRequestURI() + "\"}");
            });
            s.start();
            cells.put(code, new Cell(UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)), code, s));
            cellStatus.put(code, "ACTIVE");
        }
        registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        registry.createContext("/jwks", ex -> reply(ex, 200, new JWKSet(key.toPublicJWK()).toString()));
        registry.createContext("/v1/platform/cells", ex -> {
            registryAuth.put("tenant", String.valueOf(ex.getRequestHeaders().getFirst("X-Tenant-Id")));
            StringBuilder b = new StringBuilder("{\"data\":[");
            cells.values().forEach(c -> b.append(cellJson(c.code())).append(','));
            if (b.charAt(b.length() - 1) == ',') {
                b.setLength(b.length() - 1);
            }
            reply(ex, 200, b.append("],\"has_more\":false}").toString());
        });
        registry.createContext("/v1/platform/tenants/", ex -> {
            routingLookups.incrementAndGet();
            registryAuth.put("tenant", String.valueOf(ex.getRequestHeaders().getFirst("X-Tenant-Id")));
            String[] parts = ex.getRequestURI().getPath().split("/");
            UUID tenant = UUID.fromString(parts[4]);
            String[] p = placements.get(tenant);
            if (p == null) {
                reply(ex, 404, "{\"code\":\"not_found\"}");
                return;
            }
            reply(ex, 200, """
                    {"tenant_id":"%s","status":"ACTIVE","traffic_mode":"%s","primary":%s,"standby":%s}"""
                    .formatted(tenant, p[2], cellJson(p[0]), p[1] == null ? "null" : cellJson(p[1])));
        });
        registry.start();
    }

    String registryUrl() {
        return "http://127.0.0.1:" + registry.getAddress().getPort();
    }

    String cellJson(String code) {
        Cell c = cells.get(code);
        return """
                {"id":"%s","code":"%s","region":"eu-west-1","status":"%s","gateway_url":"%s"}"""
                .formatted(c.id(), code, cellStatus.get(code), c.url());
    }

    void place(UUID tenant, String primary, String standby, String trafficMode) {
        placements.put(tenant, new String[] {primary, standby, trafficMode});
    }

    String token(UUID tenant) {
        return sign(key, tenant, AUDIENCE, Instant.now().plusSeconds(300));
    }

    String sign(RSAKey with, UUID tenant, String audience, Instant expires) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject("user-" + tenant)
                    .audience(audience).expirationTime(Date.from(expires)).issueTime(new Date())
                    .claim("tenant_id", tenant.toString()).claim("scope", "accounts:read").build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(with.getKeyID())
                    .type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(with));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Override
    public void close() {
        cells.values().forEach(c -> c.server().stop(0));
        registry.stop(0);
    }
}
