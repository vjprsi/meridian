package io.meridian.onboarding.infrastructure;

import io.meridian.onboarding.application.PayloadStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves inline payloads: {@code data:application/json;base64,...} or gzip-compressed
 * {@code data:application/gzip;base64,...} (payload_uri is limited to 500 characters, so inline sync only suits small
 * batches). Object-store locations (s3://, https://) need the object-store adapter and are reported unavailable.
 */
@Component
public class DataUriPayloadStore implements PayloadStore {
    private static final String JSON = "data:application/json;base64,";
    private static final String GZIP = "data:application/gzip;base64,";
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private final JsonMapper json;

    public DataUriPayloadStore(JsonMapper json) {
        this.json = json;
    }

    @Override
    public Optional<JsonNode> load(String uri) {
        if (uri == null) {
            return Optional.empty();
        }
        try {
            if (uri.startsWith(JSON)) {
                byte[] raw = Base64.getDecoder().decode(uri.substring(JSON.length()));
                return Optional.of(json.readTree(new String(raw, StandardCharsets.UTF_8)));
            }
            if (uri.startsWith(GZIP)) {
                byte[] raw = Base64.getDecoder().decode(uri.substring(GZIP.length()));
                try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                    byte[] text = in.readNBytes(MAX_BYTES);
                    return Optional.of(json.readTree(new String(text, StandardCharsets.UTF_8)));
                }
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
