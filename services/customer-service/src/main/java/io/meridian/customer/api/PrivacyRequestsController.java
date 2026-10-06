package io.meridian.customer.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.customer.api.generated.PrivacyRequestsApi;
import io.meridian.customer.api.generated.model.PrivacyRequest;
import io.meridian.customer.api.generated.model.PrivacyRequestCreate;
import io.meridian.customer.api.generated.model.PrivacyRequestUpdate;
import io.meridian.customer.api.generated.model.PrivacyRequestsRejectRequest;
import io.meridian.customer.api.generated.model.PrivacyRequestsVerifyIdentityRequest;
import io.meridian.customer.application.PrivacyService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Data subject requests: identity verification, execution (export / erasure / objection) and rejection. */
@RestController
public class PrivacyRequestsController implements PrivacyRequestsApi {
    private final PrivacyService privacy;
    private final Http http;

    public PrivacyRequestsController(PrivacyService privacy, Http http) {
        this.privacy = privacy;
        this.http = http;
    }

    @Override
    public ResponseEntity<PrivacyRequest> privacyRequestsCreate(String idempotencyKey, PrivacyRequestCreate body) {
        return http.created(idempotencyKey, "privacy-requests.create", null, body, PrivacyRequest.class, () -> privacy.create(body));
    }

    @Override
    public ResponseEntity<PrivacyRequest> privacyRequestsUpdate(UUID id, String ifMatch, PrivacyRequestUpdate body) {
        TenantContext.require();
        ObjectNode row = privacy.update(id, ifMatch, body);
        return ResponseEntity.ok().eTag("\"" + row.get("row_version").asString() + "\"").body(http.read(row, PrivacyRequest.class));
    }

    @Override
    public ResponseEntity<PrivacyRequest> privacyRequestsVerifyIdentity(UUID id, String idempotencyKey,
                                                                        PrivacyRequestsVerifyIdentityRequest body) {
        String method = http.tree(body).get("method").asString();
        return http.ok(idempotencyKey, "privacy-requests.verify-identity", id, body, PrivacyRequest.class,
                () -> privacy.verifyIdentity(id, method));
    }

    @Override
    public ResponseEntity<PrivacyRequest> privacyRequestsExecute(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "privacy-requests.execute", id, null, PrivacyRequest.class, () -> privacy.execute(id));
    }

    @Override
    public ResponseEntity<PrivacyRequest> privacyRequestsReject(UUID id, String idempotencyKey, PrivacyRequestsRejectRequest body) {
        String reason = http.tree(body).get("reason").asString();
        return http.ok(idempotencyKey, "privacy-requests.reject", id, body, PrivacyRequest.class, () -> privacy.reject(id, reason));
    }
}
