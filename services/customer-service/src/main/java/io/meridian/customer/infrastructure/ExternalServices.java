package io.meridian.customer.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Outbound calls to other Meridian services: one-time passwords go out through notification-service, privacy
 * exports are stored as restricted documents in document-service. Message bodies are built here so that no
 * secret or personal data passes through logs.
 */
@Component
public class ExternalServices {
    private final ServiceClient client;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ExternalServices(ServiceClient client) {
        this.client = client;
    }

    /**
     * Rejects customer creation when legal_entity_id points to a legal entity that does not exist in the
     * caller's tenant (referential integrity, F-REF). Only a definitive 404 from tenant-service rejects;
     * auth / 5xx / timeout are allowed through so a transient downstream problem never blocks onboarding.
     */
    public void requireLegalEntity(String legalEntityId) {
        if (legalEntityId == null || legalEntityId.isBlank()) {
            return;
        }
        try {
            client.get("tenant-service", "/v1/legal-entities/" + legalEntityId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("legal_entity_not_found",
                        "legal_entity_id " + legalEntityId + " does not exist");
            }
            // transient / auth downstream issue: do not block onboarding on it
        }
    }

    /** Sends a verification code to a contact point; the code itself is never stored or logged in clear. */
    public void sendOtp(UUID customerId, UUID credentialId, String channel, String destination, String code,
                        OffsetDateTime expiresAt, String language) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipient_type", "CUSTOMER");
        body.put("recipient_id", customerId.toString());
        body.put("channel", channel);
        body.put("destination", destination);
        body.put("category", "SECURITY");
        body.put("priority", "HIGH");
        body.put("subject", "Your verification code");
        body.put("body", "Your Meridian verification code is " + code + ". It expires in 10 minutes. "
                + "Never share this code with anyone.");
        body.put("expires_at", expiresAt.toString());
        body.put("locale", language == null ? "en" : language);
        body.put("source_event_type", "party.customer_contact.verification_requested");
        client.post("notification-service", "/v1/notifications", body, "otp:" + credentialId);
    }

    /**
     * Registers a privacy export as a RESTRICTED document owned by the customer and uploads the content when the
     * document service hands out an upload URL. Returns the document id.
     */
    public UUID storeExport(UUID customerId, UUID privacyRequestId, byte[] content, String sha256, Map<String, Object> sections) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("owner_type", "CUSTOMER");
        doc.put("owner_id", customerId.toString());
        doc.put("document_type", "EXPORT");
        doc.put("file_name", "privacy-export-" + privacyRequestId + ".json");
        doc.put("content_type", "application/json");
        doc.put("classification", "RESTRICTED");
        doc.put("uploaded_via", "customer-service");
        doc.put("metadata", Map.of("privacy_request_id", privacyRequestId.toString(), "sha256", sha256,
                "size_bytes", content.length, "sections", sections));
        JsonNode created = client.post("document-service", "/v1/documents", doc, "prq-export:" + privacyRequestId);
        String id = created.path("id").asString("");
        if (id.isBlank()) {
            throw new ApiException(502, "upstream_error", "document-service did not return a document id");
        }
        UUID documentId = UUID.fromString(id);
        JsonNode upload = client.post("document-service", "/v1/documents/" + documentId + "/upload-url",
                Map.of(), "prq-upload:" + privacyRequestId);
        String url = upload == null ? "" : upload.path("upload_url").asString(upload.path("url").asString(""));
        if (!url.isBlank()) {
            put(url, content);
        }
        return documentId;
    }

    private void put(String url, byte[] content) {
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(), HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() >= 300) {
                throw new ApiException(502, "upstream_error", "export upload failed with HTTP " + r.statusCode());
            }
        } catch (IOException e) {
            throw new ApiException(502, "upstream_error", "export upload failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "upstream_error", "export upload interrupted");
        }
    }
}
