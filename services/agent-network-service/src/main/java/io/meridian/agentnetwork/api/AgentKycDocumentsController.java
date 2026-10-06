package io.meridian.agentnetwork.api;

import io.meridian.agentnetwork.api.generated.AgentKycDocumentsApi;
import io.meridian.agentnetwork.api.generated.model.AgentKycDocument;
import io.meridian.agentnetwork.api.generated.model.AgentKycDocumentCreate;
import io.meridian.agentnetwork.api.generated.model.AgentKycDocumentsVerifyRequest;
import io.meridian.agentnetwork.application.ComplianceService;
import io.meridian.common.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** KYB documents of agents; verified by a checker other than the uploader and retained once verified. */
@RestController
public class AgentKycDocumentsController implements AgentKycDocumentsApi {
    private final ComplianceService compliance;
    private final Actions actions;

    public AgentKycDocumentsController(ComplianceService compliance, Actions actions) {
        this.compliance = compliance;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<AgentKycDocument> agentKycDocumentsCreate(String idempotencyKey, AgentKycDocumentCreate body) {
        return actions.create(idempotencyKey, body, AgentKycDocument.class, () -> compliance.createDocument(actions.tree(body)));
    }

    @Override
    public ResponseEntity<AgentKycDocument> agentKycDocumentsVerify(UUID id, String idempotencyKey,
                                                                   AgentKycDocumentsVerifyRequest body) {
        return actions.action(idempotencyKey, id, "verify", body, AgentKycDocument.class,
                () -> compliance.verifyDocument(id, Http.text(actions.tree(body), "comment")));
    }

    @Override
    public ResponseEntity<Void> agentKycDocumentsDelete(UUID id) {
        TenantContext.require();
        compliance.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }
}
