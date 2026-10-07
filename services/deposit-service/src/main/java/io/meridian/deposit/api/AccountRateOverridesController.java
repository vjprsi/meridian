package io.meridian.deposit.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.deposit.api.generated.AccountRateOverridesApi;
import io.meridian.deposit.api.generated.model.AccountRateOverride;
import io.meridian.deposit.api.generated.model.AccountRateOverrideCreate;
import io.meridian.deposit.api.generated.model.AccountRateOverrideUpdate;
import io.meridian.deposit.api.generated.model.AccountRateOverridesApproveRequest;
import io.meridian.deposit.api.generated.model.AccountRateOverridesRejectRequest;
import io.meridian.deposit.application.RateOverrideService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account rate overrides under maker-checker (SEC-20): create opens a PENDING_APPROVAL override (and a workflow
 * request), approve / reject are checker actions (four-eyes); PATCH and DELETE go through the same rules instead of
 * generic CRUD (see {@link RateOverrideService}).
 */
@RestController
public class AccountRateOverridesController implements AccountRateOverridesApi {
    private final RateOverrideService overrides;
    private final Actions actions;

    public AccountRateOverridesController(RateOverrideService overrides, Actions actions) {
        this.overrides = overrides;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<AccountRateOverride> accountRateOverridesCreate(String idempotencyKey,
                                                                         AccountRateOverrideCreate body) {
        return actions.create(idempotencyKey, body, AccountRateOverride.class, () -> overrides.create(body));
    }

    @Override
    public ResponseEntity<AccountRateOverride> accountRateOverridesUpdate(UUID id, String ifMatch,
                                                                         AccountRateOverrideUpdate body) {
        TenantContext.require();
        Integer version = null;
        if (ifMatch != null && !ifMatch.isBlank()) {
            try {
                version = Integer.valueOf(ifMatch.replace("W/", "").replace("\"", "").trim());
            } catch (NumberFormatException e) {
                throw io.meridian.common.error.ApiException.badRequest("invalid_if_match", "If-Match must be the row version");
            }
        }
        return ResponseEntity.ok(actions.model(overrides.update(id, version, body), AccountRateOverride.class));
    }

    @Override
    public ResponseEntity<Void> accountRateOverridesDelete(UUID id) {
        TenantContext.require();
        overrides.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AccountRateOverride> accountRateOverridesApprove(UUID id, String idempotencyKey,
                                                                          AccountRateOverridesApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, AccountRateOverride.class,
                () -> overrides.approve(id, body == null ? null : Http.text(actions.tree(body), "comment")));
    }

    @Override
    public ResponseEntity<AccountRateOverride> accountRateOverridesReject(UUID id, String idempotencyKey,
                                                                         AccountRateOverridesRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, AccountRateOverride.class,
                () -> overrides.reject(id, Http.text(actions.tree(body), "reason")));
    }
}
