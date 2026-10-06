package io.meridian.rewards.api;

import io.meridian.rewards.api.generated.PointsAdjustmentsApi;
import io.meridian.rewards.api.generated.model.PointsAdjustment;
import io.meridian.rewards.api.generated.model.PointsAdjustmentsApproveRequest;
import io.meridian.rewards.api.generated.model.PointsAdjustmentsRejectRequest;
import io.meridian.rewards.application.AccountService;
import io.meridian.rewards.application.Json;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Maker-checker decisions on manual points adjustments. */
@RestController
public class PointsAdjustmentsController implements PointsAdjustmentsApi {
    private final AccountService accounts;
    private final Http http;

    public PointsAdjustmentsController(AccountService accounts, Http http) {
        this.accounts = accounts;
        this.http = http;
    }

    @Override
    public ResponseEntity<PointsAdjustment> pointsAdjustmentsApprove(UUID id, String idempotencyKey, PointsAdjustmentsApproveRequest body) {
        String comment = Json.text(http.tree(body), "comment");
        return http.ok(idempotencyKey, "adjustment.approve", id, body, PointsAdjustment.class, () -> accounts.approveAdjustment(id, comment));
    }

    @Override
    public ResponseEntity<PointsAdjustment> pointsAdjustmentsReject(UUID id, String idempotencyKey, PointsAdjustmentsRejectRequest body) {
        String reason = Json.required(http.tree(body), "reason");
        return http.ok(idempotencyKey, "adjustment.reject", id, body, PointsAdjustment.class, () -> accounts.rejectAdjustment(id, reason));
    }
}
