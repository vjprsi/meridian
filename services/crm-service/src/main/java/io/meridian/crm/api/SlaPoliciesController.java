package io.meridian.crm.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.crm.api.generated.SlaPoliciesApi;
import io.meridian.crm.api.generated.model.SlaPolicy;
import io.meridian.crm.api.generated.model.SlaPolicyCreate;
import io.meridian.crm.api.generated.model.SlaPolicyUpdate;
import io.meridian.crm.application.SlaService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** SLA policies with validated business-hours calendars and escalation rules. */
@RestController
public class SlaPoliciesController implements SlaPoliciesApi {
    private final SlaService sla;
    private final Http http;

    public SlaPoliciesController(SlaService sla, Http http) {
        this.sla = sla;
        this.http = http;
    }

    @Override
    public ResponseEntity<SlaPolicy> slaPoliciesCreate(String idempotencyKey, SlaPolicyCreate body) {
        return http.created(idempotencyKey, "sla-policies.create", body, SlaPolicy.class, () -> sla.create(body));
    }

    @Override
    public ResponseEntity<SlaPolicy> slaPoliciesUpdate(UUID id, String ifMatch, SlaPolicyUpdate body) {
        TenantContext.require();
        return http.withEtag(sla.update(id, ifMatch, body), SlaPolicy.class);
    }

    @Override
    public ResponseEntity<Void> slaPoliciesDelete(UUID id) {
        TenantContext.require();
        sla.delete(id);
        return ResponseEntity.noContent().build();
    }
}
