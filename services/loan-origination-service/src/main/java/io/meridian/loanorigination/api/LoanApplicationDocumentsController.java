package io.meridian.loanorigination.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.loanorigination.api.generated.LoanApplicationDocumentsApi;
import io.meridian.loanorigination.api.generated.model.LoanApplicationDocument;
import io.meridian.loanorigination.api.generated.model.LoanApplicationDocumentCreate;
import io.meridian.loanorigination.api.generated.model.LoanApplicationDocumentUpdate;
import io.meridian.loanorigination.api.generated.model.LoanApplicationDocumentsRejectRequest;
import io.meridian.loanorigination.application.ApplicationService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Documents requested and supplied for an application, with four-eyes verification. */
@RestController
public class LoanApplicationDocumentsController implements LoanApplicationDocumentsApi {
    private final ApplicationService applications;
    private final CrudEngine crud;
    private final Actions actions;

    public LoanApplicationDocumentsController(ApplicationService applications, CrudEngine crud, Actions actions) {
        this.applications = applications;
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<LoanApplicationDocument> loanApplicationDocumentsCreate(UUID loanApplicationId, String idempotencyKey,
                                                                                 LoanApplicationDocumentCreate body) {
        return actions.create(idempotencyKey, Map.of("application", loanApplicationId.toString(), "body", actions.tree(body)),
                LoanApplicationDocument.class, () -> applications.createDocument(loanApplicationId, body));
    }

    @Override
    public ResponseEntity<LoanApplicationDocument> loanApplicationDocumentsUpdate(UUID loanApplicationId, UUID id, String ifMatch,
                                                                                 LoanApplicationDocumentUpdate body) {
        TenantContext.require();
        applications.checkDocumentUpdate(loanApplicationId, id, actions.tree(body));
        crud.update("loan_application_document", LoanApplicationDocument.class, body, ifMatch,
                Crud.params().with("loan_application_id", loanApplicationId).with("id", id));
        // a document attached to a requested or rejected item becomes UPLOADED (ready for verification)
        LoanApplicationDocument doc = actions.model(applications.afterDocumentUpdate(loanApplicationId, id),
                LoanApplicationDocument.class);
        return ResponseEntity.ok().eTag("\"" + doc.getRowVersion() + "\"").body(doc);
    }

    @Override
    public ResponseEntity<LoanApplicationDocument> loanApplicationDocumentsVerify(UUID loanApplicationId, UUID id,
                                                                                 String idempotencyKey) {
        return actions.action(idempotencyKey, id, "verify", null, LoanApplicationDocument.class,
                () -> applications.verifyDocument(loanApplicationId, id));
    }

    @Override
    public ResponseEntity<LoanApplicationDocument> loanApplicationDocumentsReject(UUID loanApplicationId, UUID id,
                                                                                 String idempotencyKey,
                                                                                 LoanApplicationDocumentsRejectRequest body) {
        String reason = Http.text(actions.tree(body), "rejection_reason");
        return actions.action(idempotencyKey, id, "reject", body, LoanApplicationDocument.class,
                () -> applications.rejectDocument(loanApplicationId, id, reason));
    }
}
