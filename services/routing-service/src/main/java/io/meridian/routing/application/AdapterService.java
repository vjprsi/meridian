package io.meridian.routing.application;

import static io.meridian.routing.infrastructure.Rows.str;

import io.meridian.common.error.ApiException;
import io.meridian.routing.config.RoutingProperties;
import io.meridian.routing.infrastructure.Rows;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

/** Rail adapters: connection tests (real HTTP echo with timeout) and credential rotation to new vault references. */
@Service
public class AdapterService {
    static final String ADAPTER = "rail_adapter";
    /** Vault / secret-manager reference, never a literal secret. */
    private static final Pattern SECRET_REF = Pattern.compile("(vault:|secret/|secretsmanager:|kv/)[A-Za-z0-9_./:@-]{3,240}");

    private final Rows rows;
    private final Events events;
    private final RoutingProperties props;

    public AdapterService(Rows rows, Events events, RoutingProperties props) {
        this.rows = rows;
        this.events = events;
        this.props = props;
    }

    /** Sends an HTTP GET echo to the adapter endpoint (outside any transaction), then records the result. */
    public ObjectNode testConnection(UUID id) {
        ObjectNode adapter = rows.get(ADAPTER, id);
        String endpoint = str(adapter, "endpoint_url");
        ObjectNode result = rows.object();
        result.put("rail_adapter_id", id.toString());
        result.put("endpoint_url", endpoint);
        OffsetDateTime checkedAt = rows.scalar("select now()", OffsetDateTime.class).orElseThrow();
        result.put("checked_at", checkedAt.toString());
        if (endpoint == null || !(endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
            result.put("reachable", false);
            result.put("error", endpoint == null ? "endpoint_url is not configured" : "only http(s) endpoints can be tested");
        } else {
            long start = System.nanoTime();
            try {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(props.testTimeoutMs()))
                        .followRedirects(HttpClient.Redirect.NEVER).build();
                HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(endpoint))
                        .timeout(Duration.ofMillis(props.testTimeoutMs())).GET()
                        .header("X-Meridian-Heartbeat", "1").build(), HttpResponse.BodyHandlers.discarding());
                result.put("http_status", r.statusCode());
                result.put("reachable", r.statusCode() < 500);
                if (r.statusCode() >= 500) {
                    result.put("error", "endpoint answered HTTP " + r.statusCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.put("reachable", false);
                result.put("error", "interrupted");
            } catch (Exception e) {
                result.put("reachable", false);
                result.put("error", e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
            result.put("latency_ms", (System.nanoTime() - start) / 1_000_000);
        }
        boolean ok = result.path("reachable").asBoolean(false);
        rows.execute("update rail_adapter set last_test_result = ?::jsonb"
                + (ok ? ", last_heartbeat_at = now()" : "") + " where id = ?",
                rows.json().writeValueAsString(result), id);
        return result;
    }

    @Transactional
    public ObjectNode rotate(UUID id, String newRef, String reason) {
        if (newRef == null || !SECRET_REF.matcher(newRef.trim()).matches()) {
            throw ApiException.unprocessable("invalid_credentials_ref",
                    "credentials_ref must be a secret reference (vault:, secret/, kv/ or secretsmanager:), never a secret");
        }
        ObjectNode adapter = rows.lock(ADAPTER, id);
        if ("RETIRED".equals(str(adapter, "status"))) {
            throw ApiException.conflict("invalid_transition", "Retired adapters cannot rotate credentials");
        }
        String current = str(adapter, "credentials_ref");
        if (newRef.trim().equals(current)) {
            throw ApiException.unprocessable("credentials_unchanged", "The new credentials_ref must differ from the current one");
        }
        rows.execute("""
                update rail_adapter set previous_credentials_ref = credentials_ref, credentials_ref = ?,
                  credentials_version = credentials_version + 1, credentials_rotated_at = now() where id = ?""",
                newRef.trim(), id);
        ObjectNode row = rows.get(ADAPTER, id);
        ObjectNode event = row.deepCopy();
        event.put("rotation_reason", reason);
        events.publish("payments.rail_adapter.credentials_rotated", ADAPTER, event);
        return row;
    }
}
