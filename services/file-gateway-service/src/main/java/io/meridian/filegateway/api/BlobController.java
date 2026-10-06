package io.meridian.filegateway.api;

import io.meridian.filegateway.infrastructure.FileStore;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves pre-signed download links issued by the {@code /download} operations (not in the generated contract;
 * stands in for the object store's own signed URLs). The HMAC signature and expiry authorise the request.
 */
@RestController
public class BlobController {
    private final FileStore store;

    public BlobController(FileStore store) {
        this.store = store;
    }

    @GetMapping("/v1/files/blobs")
    public ResponseEntity<byte[]> blob(@RequestParam("key") String key, @RequestParam("expires") long expires,
                                       @RequestParam("signature") String signature) {
        byte[] content = store.readSigned(key, expires, signature);
        String name = key.substring(key.lastIndexOf('/') + 1);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(content);
    }
}
