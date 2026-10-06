package io.meridian.limits.api;

import io.meridian.limits.api.generated.LimitOverridesApi;
import io.meridian.limits.api.generated.model.LimitOverride;
import io.meridian.limits.api.generated.model.LimitOverrideCreate;
import io.meridian.limits.api.generated.model.LimitOverridesApproveRequest;
import io.meridian.limits.api.generated.model.LimitOverridesRejectRequest;
import io.meridian.limits.api.generated.model.LimitOverridesRevokeRequest;
import io.meridian.limits.application.LimitAdminService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Temporary limit overrides with four-eyes approval. */
@RestController
public class LimitOverridesController implements LimitOverridesApi {
    private final LimitAdminService admin;
    private final Http http;

    public LimitOverridesController(LimitAdminService admin, Http http) {
        this.admin = admin;
        this.http = http;
    }

    @Override
    public ResponseEntity<LimitOverride> limitOverridesCreate(String idempotencyKey, LimitOverrideCreate body) {
        return http.once(idempotencyKey, body, LimitOverride.class,
                () -> http.respond(HttpStatus.CREATED, admin.requestOverride(http.tree(body)), LimitOverride.class));
    }

    @Override
    public ResponseEntity<LimitOverride> limitOverridesApprove(UUID id, String idempotencyKey, LimitOverridesApproveRequest body) {
        String comment = Http.text(http.tree(body), "comment");
        return http.once(idempotencyKey, body, LimitOverride.class,
                () -> http.ok(admin.approveOverride(id, comment), LimitOverride.class));
    }

    @Override
    public ResponseEntity<LimitOverride> limitOverridesReject(UUID id, String idempotencyKey, LimitOverridesRejectRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, LimitOverride.class,
                () -> http.ok(admin.rejectOverride(id, reason), LimitOverride.class));
    }

    @Override
    public ResponseEntity<LimitOverride> limitOverridesRevoke(UUID id, String idempotencyKey, LimitOverridesRevokeRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, LimitOverride.class,
                () -> http.ok(admin.revokeOverride(id, reason), LimitOverride.class));
    }
}
