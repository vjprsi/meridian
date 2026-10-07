package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyKycApi;
import io.meridian.customerchannel.api.generated.model.KycDocument;
import io.meridian.customerchannel.api.generated.model.KycDocumentPage;
import io.meridian.customerchannel.api.generated.model.KycProfile;
import io.meridian.customerchannel.api.generated.model.MyKycDocumentsRequest;
import io.meridian.customerchannel.application.DocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer's KYC status and evidence. */
@RestController
public class MyKycController implements MyKycApi {
    private final DocumentService documents;
    private final Http http;

    public MyKycController(DocumentService documents, Http http) {
        this.documents = documents;
        this.http = http;
    }

    @Override
    public ResponseEntity<KycProfile> myKycProfile() {
        return http.ok(documents.kycProfile(), KycProfile.class);
    }

    @Override
    public ResponseEntity<KycDocumentPage> myKycDocuments(Integer limit, String cursor) {
        return http.ok(documents.kycDocuments(limit, cursor), KycDocumentPage.class);
    }

    @Override
    public ResponseEntity<KycDocument> myKycAddDocuments(String idempotencyKey, MyKycDocumentsRequest body) {
        return http.ok(documents.submitKycDocument(http.body(body), idempotencyKey), KycDocument.class);
    }
}
