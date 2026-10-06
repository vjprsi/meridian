package io.meridian.loanorigination.application;

import static io.meridian.loanorigination.infrastructure.Rows.uuid;

import io.meridian.approvals.Approvals;
import io.meridian.loanorigination.infrastructure.Rows;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Central maker-checker (G11) housekeeping shared by the application lifecycle: when an application is withdrawn or
 * expires, the workflow-service inbox items of its pending credit decision requests and unverified documents are
 * cancelled (after commit). Best effort: a stale inbox item is harmless, the approval handlers ignore decisions for
 * resources no longer awaiting them.
 */
@Component
public class OriginationApprovals {
    private final Rows rows;
    private final Approvals approvals;

    public OriginationApprovals(Support support, Approvals approvals) {
        this.rows = support.rows();
        this.approvals = approvals;
    }

    /** Call before the application's pending requests are cancelled locally (it reads them while still pending). */
    void cancelForApplication(UUID appId, String reason) {
        if (!approvals.centralised()) {
            return;
        }
        rows.query("""
                select row_to_json(r)::text from credit_decision_request r
                where loan_application_id = ? and status = 'PENDING_APPROVAL'""", appId)
                .forEach(r -> approvals.cancelOpen(DecisionService.APPROVAL_RESOURCE, uuid(r, "id"), null, reason));
        rows.query("""
                select row_to_json(d)::text from loan_application_document d
                where loan_application_id = ? and status = 'UPLOADED'""", appId)
                .forEach(d -> approvals.cancelOpen(ApplicationService.DOCUMENT_APPROVAL_RESOURCE, uuid(d, "id"), null, reason));
    }
}
