package io.meridian.reconciliation.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.reconciliation.api.generated.ReconAdjustmentsApi;
import io.meridian.reconciliation.api.generated.model.ReconAdjustment;
import io.meridian.reconciliation.api.generated.model.ReconAdjustmentCreate;
import io.meridian.reconciliation.api.generated.model.ReconAdjustmentsRejectRequest;
import io.meridian.reconciliation.application.AdjustmentService;
import io.meridian.reconciliation.application.Fields;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Adjustment / write-off requests for breaks under four-eyes (maker requests, a different checker decides). */
@RestController
public class ReconAdjustmentsController implements ReconAdjustmentsApi {
    private final AdjustmentService adjustments;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public ReconAdjustmentsController(AdjustmentService adjustments, Idempotency idempotency, Responses responses,
                                      JsonMapper json) {
        this.adjustments = adjustments;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<ReconAdjustment> reconAdjustmentsCreate(String idempotencyKey, ReconAdjustmentCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, ReconAdjustment.class,
                () -> responses.created(adjustments.request(body), ReconAdjustment.class));
    }

    @Override
    public ResponseEntity<ReconAdjustment> reconAdjustmentsApprove(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, responses.action("approve", id, null), ReconAdjustment.class,
                () -> responses.ok(adjustments.approve(id), ReconAdjustment.class));
    }

    @Override
    public ResponseEntity<ReconAdjustment> reconAdjustmentsReject(UUID id, String idempotencyKey,
                                                                  ReconAdjustmentsRejectRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("reject", id, req), ReconAdjustment.class,
                () -> responses.ok(adjustments.reject(id, Fields.text(req, "reason")), ReconAdjustment.class));
    }
}
