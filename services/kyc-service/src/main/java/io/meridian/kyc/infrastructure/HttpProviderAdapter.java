package io.meridian.kyc.infrastructure;

import io.meridian.kyc.application.ProviderAdapter;
import io.meridian.web.outbound.OutboundHttp;
import io.meridian.web.outbound.OutboundUrlPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
 * The tenant-configured base_url goes through the shared {@link OutboundUrlPolicy} (SEC-18): https only, public
 * addresses only (pinned at connect time), no redirects, bounded response; failures are reported as categories only.
 */
@Component
@Order(100)
public class HttpProviderAdapter implements ProviderAdapter, org.springframework.beans.factory.DisposableBean {
    private final JsonMapper json;
    private final OutboundHttp http;

    public HttpProviderAdapter(JsonMapper json, OutboundUrlPolicy policy) {
        this.json = json;
        this.http = new OutboundHttp(policy.with(policy.settings()
                .withTimeouts(Duration.ofSeconds(3), Duration.ofSeconds(30), Duration.ofSeconds(30))
                .withMaxResponseBytes(256 * 1024)));
    }

    @Override
    public void destroy() throws Exception {
        http.close();
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
            OutboundHttp.Response res = http.execute("POST", trim(p.baseUrl()) + "/checks", Map.of(),
                    json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8), "application/json",
                    Duration.ofMillis(p.timeoutMs()));
            if (res.status() >= 300) {
                return error("PROVIDER_HTTP_" + res.status());
            }
            if (res.truncated()) {
                return error("PROVIDER_RESPONSE_TOO_LARGE");
            }
            JsonNode n = json.readTree(new String(res.body(), StandardCharsets.UTF_8));
            List<String> codes = new ArrayList<>();
            n.path("reason_codes").forEach(c -> codes.add(c.asString()));
            return new CheckResult(n.path("status").asString("ERROR"),
                    n.hasNonNull("score") ? new BigDecimal(n.get("score").asString()) : null, codes,
                    n.path("reference").asString(null), n.path("summary").asString(null), null, null);
        } catch (OutboundUrlPolicy.RejectedException e) {
            return error("PROVIDER_URL_NOT_ALLOWED");
        } catch (RuntimeException e) {
            return error("PROVIDER_UNREACHABLE");
        }
    }

    @Override
    public ConnectionResult testConnection(Provider p) {
        long start = System.nanoTime();
        try {
            int status = http.execute("GET", trim(p.baseUrl()) + "/health", Map.of(), null, null,
                    Duration.ofMillis(p.timeoutMs())).status();
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new ConnectionResult(status < 300, ms, "HTTP " + status);
        } catch (OutboundUrlPolicy.RejectedException e) {
            return new ConnectionResult(false, (System.nanoTime() - start) / 1_000_000,
                    "Provider URL not allowed: " + e.getMessage());
        } catch (RuntimeException e) {
            return new ConnectionResult(false, (System.nanoTime() - start) / 1_000_000, "Provider unreachable");
        }
    }

    private static CheckResult error(String code) {
        return new CheckResult("ERROR", null, List.of(code), null, "Provider call failed", null, null);
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
