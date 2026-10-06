package io.meridian.kyc.api;

import io.meridian.kyc.api.generated.KycDocumentsApi;
import io.meridian.kyc.api.generated.model.KycDocument;
import io.meridian.kyc.api.generated.model.KycDocumentCreate;
import io.meridian.kyc.api.generated.model.KycDocumentsAcceptRequest;
import io.meridian.kyc.api.generated.model.KycDocumentsRejectRequest;
import io.meridian.kyc.application.DocumentService;
import io.meridian.kyc.application.KycSupport;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** KYC documents: capture and maker-checker review. */
@RestController
public class KycDocumentsController implements KycDocumentsApi {
    private final DocumentService documents;
    private final Actions actions;
    private final KycSupport kyc;

    public KycDocumentsController(DocumentService documents, Actions actions, KycSupport kyc) {
        this.documents = documents;
        this.actions = actions;
        this.kyc = kyc;
    }

    @Override
    public ResponseEntity<KycDocument> kycDocumentsCreate(String idempotencyKey, KycDocumentCreate body) {
        return actions.created(idempotencyKey, "document.create", body, KycDocument.class, () -> documents.create(body));
    }

    @Override
    public ResponseEntity<KycDocument> kycDocumentsAccept(UUID id, String idempotencyKey, KycDocumentsAcceptRequest body) {
        return actions.ok(idempotencyKey, "document.accept", id, body, KycDocument.class, () -> documents.accept(id));
    }

    @Override
    public ResponseEntity<KycDocument> kycDocumentsReject(UUID id, String idempotencyKey, KycDocumentsRejectRequest body) {
        String reason = KycSupport.text(kyc.tree(body), "reason");
        return actions.ok(idempotencyKey, "document.reject", id, body, KycDocument.class,
                () -> documents.reject(id, reason));
    }
}
