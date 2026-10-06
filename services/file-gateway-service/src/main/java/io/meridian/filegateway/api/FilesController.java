package io.meridian.filegateway.api;

import io.meridian.filegateway.api.generated.FilesApi;
import io.meridian.filegateway.infrastructure.FileStore;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pre-signed object downloads ({@code GET /v1/files/blobs}, a public operation: the HMAC signature and expiry
 * issued by the {@code /download} operations authorise the request, no bearer token or tenant header).
 */
@RestController
public class FilesController implements FilesApi {
    private final FileStore store;

    public FilesController(FileStore store) {
        this.store = store;
    }

    /** Signed blob download: raw bytes, unchanged (binary-safe for PGP and other payloads). */
    @Override
    public ResponseEntity<org.springframework.core.io.Resource> filesBlobs(String key, Long expires, String signature) {
        byte[] content = store.readSigned(key, expires, signature);
        String name = key.substring(key.lastIndexOf('/') + 1);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(new org.springframework.core.io.ByteArrayResource(content));
    }
}
