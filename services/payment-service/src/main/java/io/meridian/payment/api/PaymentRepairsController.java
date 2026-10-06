package io.meridian.payment.api;

import io.meridian.payment.api.generated.PaymentRepairsApi;
import io.meridian.payment.api.generated.model.PaymentRepair;
import io.meridian.payment.api.generated.model.PaymentRepairsAssignRequest;
import io.meridian.payment.api.generated.model.PaymentRepairsCancelRequest;
import io.meridian.payment.api.generated.model.PaymentRepairsResubmitRequest;
import io.meridian.payment.application.RepairService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Repair queue: assign, correct and resubmit, or cancel payments that failed enrichment or routing. */
@RestController
public class PaymentRepairsController implements PaymentRepairsApi {
    private final Actions actions;
    private final RepairService repairs;

    public PaymentRepairsController(Actions actions, RepairService repairs) {
        this.actions = actions;
        this.repairs = repairs;
    }

    @Override
    public ResponseEntity<PaymentRepair> paymentRepairsAssign(UUID id, String idempotencyKey, PaymentRepairsAssignRequest body) {
        String to = Actions.text(actions.tree(body), "assigned_to");
        return actions.action(idempotencyKey, id, "assign", body, PaymentRepair.class, () -> repairs.assign(id, to));
    }

    @Override
    public ResponseEntity<PaymentRepair> paymentRepairsResubmit(UUID id, String idempotencyKey, PaymentRepairsResubmitRequest body) {
        JsonNode corrections = actions.tree(body).get("corrections");
        return actions.action(idempotencyKey, id, "resubmit", body, PaymentRepair.class, () -> repairs.resubmit(id, corrections));
    }

    @Override
    public ResponseEntity<PaymentRepair> paymentRepairsCancel(UUID id, String idempotencyKey, PaymentRepairsCancelRequest body) {
        String reason = Actions.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "cancel", body, PaymentRepair.class, () -> repairs.cancel(id, reason));
    }
}
