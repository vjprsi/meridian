package io.meridian.sanctions.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** case-management-service (confirmed matches), document-service (list files) and list publisher downloads. */
@Component
public class Downstream {
    private final ServiceClient client;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public Downstream(ServiceClient client) {
        this.client = client;
    }

    /** Opens a SANCTIONS case for a confirmed match and links the screening result to it; returns the case id. */
    public UUID openSanctionsCase(JsonNode result, List<JsonNode> trueMatches, String reason) {
        String resultId = result.get("id").asString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("case_type", "SANCTIONS");
        body.put("priority", "CRITICAL");
        body.put("title", "Confirmed sanctions match for " + result.get("subject_type").asString() + " "
                + result.get("subject_id").asString());
        body.put("description", reason);
        if ("CUSTOMER".equals(result.get("subject_type").asString())) {
            body.put("customer_id", result.get("subject_id").asString());
        }
        JsonNode amlCase = client.post("case-management-service", "/v1/risk/cases", body, "screening-case:" + resultId);
        String caseId = amlCase.get("id").asString();
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("source", "SCREENING_RESULT");
        link.put("alert_id", resultId);
        String names = String.join("; ", trueMatches.stream().map(m -> m.get("matched_name").asString()).toList());
        link.put("summary", cut("Confirmed match: " + names, 500));
        link.put("added_reason", cut(reason, 255));
        client.post("case-management-service", "/v1/risk/cases/" + caseId + "/alerts", link, "screening-link:" + resultId);
        return UUID.fromString(caseId);
    }

    /** Content of a stored document (list file uploaded to document-service). */
    public String documentContent(UUID documentId) {
        JsonNode url = client.get("document-service", "/v1/documents/" + documentId + "/download-url");
        String u = url.path("url").asString(url.path("download_url").asString(null));
        if (u == null) {
            throw ApiException.unprocessable("document_unavailable", "document-service returned no download URL");
        }
        return download(u);
    }

    /** Downloads a list file from its publisher (source_url). */
    public String download(String url) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300) {
                throw new ApiException(502, "list_download_failed", "Download returned HTTP " + r.statusCode());
            }
            return r.body();
        } catch (IOException e) {
            throw new ApiException(502, "list_download_failed", "Download failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "list_download_failed", "Download interrupted");
        }
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
