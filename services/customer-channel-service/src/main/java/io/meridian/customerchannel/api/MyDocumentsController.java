package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyDocumentsApi;
import io.meridian.customerchannel.api.generated.model.Document;
import io.meridian.customerchannel.api.generated.model.DocumentPage;
import io.meridian.customerchannel.api.generated.model.MyDocumentsCompleteUploadRequest;
import io.meridian.customerchannel.api.generated.model.MyDocumentsCreateRequest;
import io.meridian.customerchannel.application.DocumentService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Documents the customer uploads. */
@RestController
public class MyDocumentsController implements MyDocumentsApi {
    private final DocumentService documents;
    private final Http http;

    public MyDocumentsController(DocumentService documents, Http http) {
        this.documents = documents;
        this.http = http;
    }

    @Override
    public ResponseEntity<DocumentPage> myDocumentsList(Integer limit, String cursor) {
        return http.ok(documents.documents(limit, cursor), DocumentPage.class);
    }

    @Override
    public ResponseEntity<Document> myDocumentsCreate(String idempotencyKey, MyDocumentsCreateRequest body) {
        return http.ok(documents.create(http.body(body), idempotencyKey), Document.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> myDocumentsUploadUrl(UUID documentId, String idempotencyKey) {
        return http.object(documents.uploadUrl(documentId, idempotencyKey));
    }

    @Override
    public ResponseEntity<Document> myDocumentsCompleteUpload(UUID documentId, String idempotencyKey,
                                                              MyDocumentsCompleteUploadRequest body) {
        return http.ok(documents.completeUpload(documentId, http.body(body), idempotencyKey), Document.class);
    }
}
