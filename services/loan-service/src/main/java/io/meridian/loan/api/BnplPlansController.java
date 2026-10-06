package io.meridian.loan.api;

import io.meridian.loan.api.generated.BnplPlansApi;
import io.meridian.loan.api.generated.model.BnplPlan;
import io.meridian.loan.api.generated.model.BnplPlanCreate;
import io.meridian.loan.api.generated.model.BnplPlansCancelRequest;
import io.meridian.loan.api.generated.model.BnplPlansCheckEligibilityRequest;
import io.meridian.loan.api.generated.model.BnplPlansRefundRequest;
import io.meridian.loan.application.BnplService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** BNPL plans: checkout eligibility, plan creation (loan + merchant settlement), refunds and cancellation. */
@RestController
public class BnplPlansController implements BnplPlansApi {
    private final BnplService bnpl;
    private final Actions actions;

    public BnplPlansController(BnplService bnpl, Actions actions) {
        this.bnpl = bnpl;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<Map<String, Object>> bnplPlansCheckEligibility(String idempotencyKey, BnplPlansCheckEligibilityRequest body) {
        return actions.objectAction(idempotencyKey, "bnpl-eligibility", body, () -> bnpl.eligibility(actions.tree(body)));
    }

    @Override
    public ResponseEntity<BnplPlan> bnplPlansCreate(String idempotencyKey, BnplPlanCreate body) {
        return actions.create(idempotencyKey, body, BnplPlan.class, () -> bnpl.create(actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<BnplPlan> bnplPlansRefund(UUID id, String idempotencyKey, BnplPlansRefundRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "refund", body, BnplPlan.class,
                () -> bnpl.refund(id, Http.decimal(req, "amount"), Http.text(req, "reason")));
    }

    @Override
    public ResponseEntity<BnplPlan> bnplPlansCancel(UUID id, String idempotencyKey, BnplPlansCancelRequest body) {
        return actions.action(idempotencyKey, id, "cancel", body, BnplPlan.class,
                () -> bnpl.cancel(id, Http.text(actions.tree(body), "reason")));
    }
}
