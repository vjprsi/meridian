package io.meridian.document.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.document.application.ObjectStorage;
import io.meridian.document.domain.SigV4Presigner;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * S3-compatible storage (AWS S3, SeaweedFS, MinIO) using path-style addressing and SigV4 presigned URLs.
 * Server-side writes also use a short-lived presigned PUT, so no SDK is needed.
 */
@Component
public class S3ObjectStorage implements ObjectStorage {
    private final String endpoint;
    private final String bucket;
    private final SigV4Presigner presigner;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public S3ObjectStorage(@Value("${meridian.documents.s3.endpoint:http://localhost:19000}") String endpoint,
                           @Value("${meridian.documents.s3.bucket:meridian-documents}") String bucket,
                           @Value("${meridian.documents.s3.region:us-east-1}") String region,
                           @Value("${meridian.documents.s3.access-key:meridian}") String accessKey,
                           @Value("${meridian.documents.s3.secret-key:meridian-secret}") String secretKey) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.bucket = bucket;
        this.presigner = new SigV4Presigner(accessKey, secretKey, region);
    }

    @Override
    public String uri(String key) {
        return "s3://" + bucket + "/" + key;
    }

    @Override
    public String keyOf(String storageUri) {
        String prefix = "s3://" + bucket + "/";
        if (storageUri == null || !storageUri.startsWith(prefix)) {
            throw new ApiException(409, "storage_location_unknown", "Document content is not in the configured bucket");
        }
        return storageUri.substring(prefix.length());
    }

    @Override
    public String presign(String method, String key, Duration ttl, Map<String, String> responseOverrides) {
        return presigner.presign(method, endpoint, "/" + bucket + "/" + key, responseOverrides, ttl.toSeconds(), Instant.now());
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        String url = presign("PUT", key, Duration.ofMinutes(5), Map.of());
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300) {
                throw new IllegalStateException("object store answered " + r.statusCode());
            }
        } catch (IOException e) {
            throw new IllegalStateException("object store not reachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while storing object", e);
        }
    }
}
