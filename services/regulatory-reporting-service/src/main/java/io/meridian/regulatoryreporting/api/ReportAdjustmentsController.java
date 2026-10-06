package io.meridian.regulatoryreporting.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.regulatoryreporting.api.generated.ReportAdjustmentsApi;
import io.meridian.regulatoryreporting.api.generated.model.ReportAdjustment;
import io.meridian.regulatoryreporting.api.generated.model.ReportAdjustmentsApproveRequest;
import io.meridian.regulatoryreporting.api.generated.model.ReportAdjustmentsRejectRequest;
import io.meridian.regulatoryreporting.application.AdjustmentService;
import io.meridian.regulatoryreporting.application.Fields;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Adjustment decisions (checker; four eyes). */
@RestController
public class ReportAdjustmentsController implements ReportAdjustmentsApi {
    private final AdjustmentService adjustments;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public ReportAdjustmentsController(AdjustmentService adjustments, Idempotency idempotency, Responses responses,
                                       JsonMapper json) {
        this.adjustments = adjustments;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<ReportAdjustment> reportAdjustmentsApprove(UUID id, String idempotencyKey,
                                                                    ReportAdjustmentsApproveRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("approve", id, req), ReportAdjustment.class,
                () -> responses.ok(adjustments.approve(id, Fields.text(req, "comment")), ReportAdjustment.class));
    }

    @Override
    public ResponseEntity<ReportAdjustment> reportAdjustmentsReject(UUID id, String idempotencyKey,
                                                                   ReportAdjustmentsRejectRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Fields.requireText(req, "reason");
        return idempotency.execute(idempotencyKey, responses.action("reject", id, req), ReportAdjustment.class,
                () -> responses.ok(adjustments.reject(id, reason), ReportAdjustment.class));
    }
}
