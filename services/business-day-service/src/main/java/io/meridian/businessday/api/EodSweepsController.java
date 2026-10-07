package io.meridian.businessday.api;

import io.meridian.businessday.api.generated.EodSweepsApi;
import io.meridian.businessday.api.generated.model.EodSweep;
import io.meridian.businessday.api.generated.model.EodSweepsRunRequest;
import io.meridian.businessday.application.EodTriggerService;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-operator API of the cross-tenant EOD trigger. Runs and reads require {@code platform:operator} (generated
 * scope; {@code eod_sweep} is {@code operator_only} in the spec), which {@code ScopeInterceptor} accepts only from
 * internal service tokens or users of the platform-operator tenant (SEC-15).
 */
@RestController
public class EodSweepsController implements EodSweepsApi {
    private final EodTriggerService trigger;
    private final CrudEngine crud;
    private final Idempotency idempotency;

    public EodSweepsController(EodTriggerService trigger, CrudEngine crud, Idempotency idempotency) {
        this.trigger = trigger;
        this.crud = crud;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<EodSweep> eodSweepsRun(String idempotencyKey, EodSweepsRunRequest body) {
        UUID operatorTenant = TenantContext.require();
        List<UUID> tenantIds = body == null || body.getTenantIds() == null ? List.of() : List.copyOf(body.getTenantIds());
        String slot = "op:" + operatorTenant + ":" + idempotencyKey;
        String principal = TenantContext.principal();
        return idempotency.execute(idempotencyKey, body, EodSweep.class, () -> {
            String row = trigger.sweep("OPERATOR", slot, principal, tenantIds)
                    .or(() -> trigger.sweepBySlot(slot))
                    .orElseThrow(() -> ApiException.conflict("sweep_in_progress", "A sweep with this key is running"));
            return ResponseEntity.ok(crud.read(row, EodSweep.class));
        });
    }

}
