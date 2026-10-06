package io.meridian.filegateway.infrastructure;

import io.meridian.common.error.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fetches file content from an http(s) URL (e.g. a partner's pre-signed object URL), bounded in size. */
@Component
public class ContentFetcher {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final long maxBytes;

    public ContentFetcher(@Value("${meridian.files.max-size-bytes:26214400}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public byte[] fetch(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("invalid_content_url", "content_url is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw ApiException.unprocessable("invalid_content_url", "content_url must be http(s)");
        }
        try {
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() >= 400) {
                throw new ApiException(502, "content_fetch_failed", "Fetching content_url returned HTTP " + r.statusCode());
            }
            try (InputStream in = r.body()) {
                byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
                if (data.length > maxBytes) {
                    throw ApiException.unprocessable("file_too_large", "File exceeds " + maxBytes + " bytes");
                }
                return data;
            }
        } catch (IOException e) {
            throw new ApiException(502, "content_fetch_failed", "Cannot fetch content_url: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(502, "content_fetch_failed", "Interrupted while fetching content_url");
        }
    }
}
