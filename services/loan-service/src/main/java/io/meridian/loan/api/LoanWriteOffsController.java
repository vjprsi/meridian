package io.meridian.loan.api;

import io.meridian.loan.api.generated.LoanWriteOffsApi;
import io.meridian.loan.api.generated.model.LoanWriteOff;
import io.meridian.loan.api.generated.model.LoanWriteOffsApproveRequest;
import io.meridian.loan.api.generated.model.LoanWriteOffsRejectRequest;
import io.meridian.loan.application.WriteOffService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Write-off maker-checker: approve (another user; posts to the ledger) or reject. */
@RestController
public class LoanWriteOffsController implements LoanWriteOffsApi {
    private final WriteOffService writeOffs;
    private final Actions actions;

    public LoanWriteOffsController(WriteOffService writeOffs, Actions actions) {
        this.writeOffs = writeOffs;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<LoanWriteOff> loanWriteOffsApprove(UUID id, String idempotencyKey, LoanWriteOffsApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, LoanWriteOff.class,
                () -> writeOffs.approve(id, Http.text(actions.tree(body), "comment")));
    }

    @Override
    public ResponseEntity<LoanWriteOff> loanWriteOffsReject(UUID id, String idempotencyKey, LoanWriteOffsRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, LoanWriteOff.class,
                () -> writeOffs.reject(id, Http.text(actions.tree(body), "reason")));
    }
}
