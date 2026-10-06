package io.meridian.document.application;

import java.time.Duration;
import java.util.Map;

/** Port to the object store holding document content (S3-compatible). */
public interface ObjectStorage {

    /** Storage URI ({@code s3://bucket/key}) for an object key. */
    String uri(String key);

    /** Extracts the object key from a storage URI produced by {@link #uri(String)}. */
    String keyOf(String storageUri);

    /** A presigned URL the client can use for {@code method} on the object without credentials. */
    String presign(String method, String key, Duration ttl, Map<String, String> responseOverrides);

    /** Stores server-generated content (e.g. rendered documents). */
    void put(String key, byte[] content, String contentType);
}
