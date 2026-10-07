package io.meridian.identity.application;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.identity.domain.Secrets;
import io.meridian.identity.infrastructure.IdentityStore;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** OAuth 2.1 client registration, secret rotation, suspension and usage reporting. */
@Service
public class ApiClientService {
    static final Set<String> SECRET_METHODS = Set.of("CLIENT_SECRET_BASIC", "CLIENT_SECRET_POST");

    private final IdentityStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public ApiClientService(IdentityStore store, CrudEngine crud, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
    }

    public record Issued(ObjectNode body, Map<String, String> secrets) {}

    @Transactional
    public Issued register(Object body) {
        ObjectNode v = crud.toColumns("api_client", body);
        if (!v.hasNonNull("auth_method")) {
            v.put("auth_method", "PRIVATE_KEY_JWT");
        }
        validate(v);
        v.put("status", "ACTIVE");
        v.put("client_id", Secrets.clientId());
        Map<String, String> secrets = Map.of();
        if (SECRET_METHODS.contains(v.get("auth_method").asString())) {
            String secret = Secrets.clientSecret();
            v.put("client_secret_hash", Secrets.sha256Hex(secret));
            secrets = Map.of("client_secret", secret);
        }
        String row = crud.insert("api_client", v);
        ObjectNode pub = store.publicView(row);
        outbox.publish("platform.api_client.created", "api_client", UUID.fromString(pub.get("id").asString()), pub);
        return new Issued(pub, secrets);
    }

    @Transactional
    public <T> ResponseEntity<T> update(UUID id, String ifMatch, Object body, Class<T> type) {
        ObjectNode v = crud.toColumns("api_client", body);
        ObjectNode merged = (ObjectNode) store.require("api_client", id);
        merged.setAll(v);
        validate(merged);
        return crud.update("api_client", type, body, ifMatch, Crud.params().with("id", id));
    }

    /** Issues a new client secret (returned once) and publishes {@code platform.api_client.secret_rotated}. */
    @Transactional
    public Issued rotateSecret(UUID id) {
        JsonNode c = store.lock("api_client", id);
        requireStatus(c, "ACTIVE");
        if (!SECRET_METHODS.contains(c.get("auth_method").asString())) {
            throw ApiException.unprocessable("not_confidential_client",
                    "Client authenticates with " + c.get("auth_method").asString() + " and has no secret");
        }
        String secret = Secrets.clientSecret();
        ObjectNode pub = store.publicView(store.updateReturning("""
                update api_client t set client_secret_hash = ? where t.id = ? returning row_to_json(t)::text""",
                Secrets.sha256Hex(secret), id));
        outbox.publish("platform.api_client.secret_rotated", "api_client", id, pub);
        return new Issued(pub, Map.of("client_secret", secret));
    }

    @Transactional
    public String suspend(UUID id, String reason) {
        SessionService.requireReason(reason);
        JsonNode c = store.lock("api_client", id);
        requireStatus(c, "ACTIVE");
        return changed(store.updateReturning(
                "update api_client t set status = 'SUSPENDED' where t.id = ? returning row_to_json(t)::text", id));
    }

    @Transactional
    public String reactivate(UUID id) {
        JsonNode c = store.lock("api_client", id);
        requireStatus(c, "SUSPENDED");
        if (c.hasNonNull("expires_at") && OffsetDateTime.parse(c.get("expires_at").asString()).isBefore(io.meridian.common.time.Clocks.now())) {
            throw ApiException.conflict("client_expired", "Client expired; register a new client");
        }
        return changed(store.updateReturning(
                "update api_client t set status = 'ACTIVE' where t.id = ? returning row_to_json(t)::text", id));
    }

    /** Request counts, error rate and latency from the gateway request log for [from, to). Default: last 30 days. */
    public JsonNode usage(UUID id, OffsetDateTime fromParam, OffsetDateTime toParam) {
        store.require("api_client", id);
        OffsetDateTime to = toParam == null ? io.meridian.common.time.Clocks.now() : toParam;
        OffsetDateTime from = fromParam == null ? to.minusDays(30) : fromParam;
        if (!from.isBefore(to)) {
            throw ApiException.badRequest("invalid_period", "from must be before to");
        }
        ObjectNode out = json.createObjectNode();
        out.put("api_client_id", id.toString());
        out.put("from", from.toString());
        out.put("to", to.toString());
        store.jdbc().sql("""
                select count(*), count(*) filter (where status_code >= 400), count(*) filter (where status_code >= 500),
                       coalesce(avg(latency_ms), 0), coalesce(percentile_cont(0.95) within group (order by latency_ms), 0),
                       coalesce(max(latency_ms), 0)
                from api_request_log where api_client_id = ? and occurred_at >= ? and occurred_at < ?""")
                .params(id, from, to)
                .query((rs, i) -> {
                    long total = rs.getLong(1);
                    out.put("request_count", total);
                    out.put("error_count", rs.getLong(2));
                    out.put("server_error_count", rs.getLong(3));
                    out.put("error_rate", total == 0 ? "0" : new java.math.BigDecimal(rs.getLong(2))
                            .divide(java.math.BigDecimal.valueOf(total), 4, java.math.RoundingMode.HALF_EVEN).toPlainString());
                    out.put("avg_latency_ms", Math.round(rs.getDouble(4)));
                    out.put("p95_latency_ms", Math.round(rs.getDouble(5)));
                    out.put("max_latency_ms", rs.getLong(6));
                    return null;
                }).list();
        ArrayNode ops = out.putArray("by_operation");
        store.jdbc().sql("""
                select coalesce(operation_id, method || ' ' || path) op, count(*), count(*) filter (where status_code >= 400)
                from api_request_log where api_client_id = ? and occurred_at >= ? and occurred_at < ?
                group by 1 order by 2 desc limit 50""")
                .params(id, from, to)
                .query((rs, i) -> {
                    ObjectNode o = ops.addObject();
                    o.put("operation", rs.getString(1));
                    o.put("request_count", rs.getLong(2));
                    o.put("error_count", rs.getLong(3));
                    return o;
                }).list();
        return out;
    }

    private void validate(ObjectNode v) {
        List<String> scopes = JsonItems.of(v.get("allowed_scopes"));
        if (scopes.isEmpty()) {
            throw ApiException.unprocessable("scopes_required",
                    "allowed_scopes must list at least one scope, e.g. [\"payments:read\"]");
        }
        for (String s : scopes) {
            Integer n = store.jdbc().sql("select count(*) from oauth_scope where scope = ?").param(s)
                    .query(Integer.class).single();
            if (n == 0) {
                throw ApiException.unprocessable("unknown_scope", "Scope " + s + " is not in the scope catalogue");
            }
        }
        v.set("allowed_scopes", JsonItems.canonical(json, scopes));
        String type = v.get("client_type").asString();
        String auth = v.get("auth_method").asString();
        io.meridian.identity.domain.BusinessClientBinding.problem(text(v, "business_customer_id"),
                text(v, "business_user_id"), type, scopes).ifPresent(p -> {
            throw ApiException.unprocessable(p[0], p[1]);
        });
        if ("PUBLIC_APP".equals(type) != "NONE".equals(auth)) {
            throw ApiException.unprocessable("invalid_auth_method",
                    "Public apps use auth_method NONE (PKCE); confidential clients must authenticate");
        }
        if ("PUBLIC_APP".equals(type) && JsonItems.of(v.get("redirect_uris")).isEmpty()) {
            throw ApiException.unprocessable("redirect_uris_required", "Public apps need at least one redirect URI");
        }
        if ("PRIVATE_KEY_JWT".equals(auth) && (!v.hasNonNull("jwks_uri") || !v.get("jwks_uri").asString().startsWith("https://"))) {
            throw ApiException.unprocessable("jwks_uri_required", "private_key_jwt requires an https jwks_uri");
        }
        if ("TLS_CLIENT_AUTH".equals(auth) && !v.hasNonNull("mtls_cert_thumbprint")) {
            throw ApiException.unprocessable("certificate_required", "tls_client_auth requires mtls_cert_thumbprint");
        }
        if ("TPP".equals(type)) {
            if (!v.hasNonNull("tpp_registration_id")) {
                throw ApiException.unprocessable("tpp_registration_required", "TPP clients need tpp_registration_id");
            }
            if (!v.path("fapi_profile").asBoolean(false) || SECRET_METHODS.contains(auth)) {
                throw ApiException.unprocessable("fapi_required",
                        "TPP clients must use the FAPI 2.0 profile with private_key_jwt or tls_client_auth");
            }
        }
    }

    private static String text(JsonNode v, String field) {
        return v.hasNonNull(field) && !v.get(field).asString().isBlank() ? v.get(field).asString() : null;
    }

    private static void requireStatus(JsonNode c, String expected) {
        String s = c.get("status").asString();
        if (!expected.equals(s)) {
            throw ApiException.conflict("invalid_transition", "Client is " + s + "; expected " + expected);
        }
    }

    private String changed(String row) {
        ObjectNode pub = store.publicView(row);
        outbox.publish("platform.api_client.status_changed", "api_client", UUID.fromString(pub.get("id").asString()), pub);
        return row;
    }
}
