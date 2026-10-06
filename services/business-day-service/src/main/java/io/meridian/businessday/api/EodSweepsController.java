package io.meridian.businessday.api;

import io.meridian.businessday.api.generated.EodSweepsApi;
import io.meridian.businessday.api.generated.model.EodSweep;
import io.meridian.businessday.api.generated.model.EodSweepPage;
import io.meridian.businessday.api.generated.model.EodSweepsRunRequest;
import io.meridian.businessday.application.EodTriggerService;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.MeridianProperties;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-operator API of the cross-tenant EOD trigger. Running a sweep requires {@code meridian:admin} (generated
 * scope). Sweep records span tenants, so reads require {@code meridian:admin} as well (checked here: the generator
 * grants reads of platform-global tables to {@code operations:read}).
 */
@RestController
public class EodSweepsController implements EodSweepsApi {
    private final EodTriggerService trigger;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final MeridianProperties props;

    public EodSweepsController(EodTriggerService trigger, CrudEngine crud, Idempotency idempotency,
                               MeridianProperties props) {
        this.trigger = trigger;
        this.crud = crud;
        this.idempotency = idempotency;
        this.props = props;
    }

    @Override
    public ResponseEntity<EodSweep> eodSweepsRun(String idempotencyKey, EodSweepsRunRequest body) {
        requireOperator();
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

    @Override
    public ResponseEntity<EodSweep> eodSweepsGet(UUID id) {
        requireOperator();
        return EodSweepsApi.super.eodSweepsGet(id);
    }

    @Override
    public ResponseEntity<EodSweepPage> eodSweepsList(Integer limit, String cursor, OffsetDateTime createdAfter,
                                                      OffsetDateTime createdBefore, String triggerSource, String status,
                                                      String customField1, String customField2, String customField3,
                                                      String customField4, String customField5) {
        requireOperator();
        return EodSweepsApi.super.eodSweepsList(limit, cursor, createdAfter, createdBefore, triggerSource, status,
                customField1, customField2, customField3, customField4, customField5);
    }

    /** Platform operators only (dev mode has no tokens and skips scope checks, like the generated ones). */
    private void requireOperator() {
        if (props.devMode()) {
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            Object scope = jwt.getClaims().get("scope");
            boolean admin = scope instanceof String s ? List.of(s.split(" ")).contains("meridian:admin")
                    : scope instanceof Collection<?> c && c.stream().anyMatch(x -> "meridian:admin".equals(String.valueOf(x)));
            if (admin) {
                return;
            }
        }
        throw new ApiException(403, "insufficient_scope", "This operation requires scope meridian:admin");
    }
}
