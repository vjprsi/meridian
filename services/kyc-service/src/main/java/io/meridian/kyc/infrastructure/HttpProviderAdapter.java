package io.meridian.kyc.infrastructure;

import io.meridian.kyc.application.ProviderAdapter;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generic REST adapter (Meridian provider protocol): POST {base_url}/checks returns
 * {status, score, reason_codes[], reference, summary}; GET {base_url}/health for connectivity. Vendor-specific
 * adapters (Onfido, Jumio, ...) register with a higher precedence. Errors map to an ERROR check, never an exception.
 */
@Component
@Order(100)
public class HttpProviderAdapter implements ProviderAdapter {
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public HttpProviderAdapter(JsonMapper json) {
        this.json = json;
    }

    @Override
    public boolean supports(Provider p) {
        return p.baseUrl() != null && (p.baseUrl().startsWith("http://") || p.baseUrl().startsWith("https://"));
    }

    @Override
    public CheckResult runCheck(Provider p, CheckRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_reference", r.checkId().toString());
        body.put("tenant_id", io.meridian.common.tenancy.TenantContext.require().toString()); // echoed back in callbacks
        body.put("check_type", r.checkType());
        body.put("subject_name", r.subjectName());
        body.put("document_type", r.documentType());
        body.put("document_country", r.documentCountry());
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(trim(p.baseUrl()) + "/checks"))
                    .timeout(Duration.ofMillis(p.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                return error("PROVIDER_HTTP_" + res.statusCode());
            }
            JsonNode n = json.readTree(res.body());
            List<String> codes = new ArrayList<>();
            n.path("reason_codes").forEach(c -> codes.add(c.asString()));
            return new CheckResult(n.path("status").asString("ERROR"),
                    n.hasNonNull("score") ? new BigDecimal(n.get("score").asString()) : null, codes,
                    n.path("reference").asString(null), n.path("summary").asString(null), null, null);
        } catch (IOException | RuntimeException e) {
            return error("PROVIDER_UNREACHABLE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error("PROVIDER_INTERRUPTED");
        }
    }

    @Override
    public ConnectionResult testConnection(Provider p) {
        long start = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(trim(p.baseUrl()) + "/health"))
                    .timeout(Duration.ofMillis(p.timeoutMs())).GET().build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new ConnectionResult(status < 300, ms, "HTTP " + status);
        } catch (IOException | RuntimeException e) {
            return new ConnectionResult(false, (System.nanoTime() - start) / 1_000_000, "Provider unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ConnectionResult(false, 0, "Interrupted");
        }
    }

    private static CheckResult error(String code) {
        return new CheckResult("ERROR", null, List.of(code), null, "Provider call failed", null, null);
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
