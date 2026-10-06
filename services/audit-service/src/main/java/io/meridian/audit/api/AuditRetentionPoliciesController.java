package io.meridian.audit.api;

import io.meridian.audit.api.generated.AuditRetentionPoliciesApi;
import io.meridian.audit.api.generated.model.AuditRetentionPolicy;
import io.meridian.audit.api.generated.model.AuditRetentionPolicyCreate;
import io.meridian.audit.api.generated.model.AuditRetentionPolicyUpdate;
import io.meridian.audit.application.RetentionPolicyService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Retention policies: generic CRUD with retention-period validation. */
@RestController
public class AuditRetentionPoliciesController implements AuditRetentionPoliciesApi {
    private final RetentionPolicyService policies;
    private final CrudEngine crud;
    private final io.meridian.web.idempotency.Idempotency idempotency;

    public AuditRetentionPoliciesController(RetentionPolicyService policies, CrudEngine crud,
                                            io.meridian.web.idempotency.Idempotency idempotency) {
        this.policies = policies;
        this.crud = crud;
        this.idempotency = idempotency;
    }

    @Override
    public ResponseEntity<AuditRetentionPolicy> auditRetentionPoliciesCreate(String idempotencyKey,
                                                                             AuditRetentionPolicyCreate body) {
        io.meridian.common.tenancy.TenantContext.require();
        // Validation runs inside the idempotent section so that a replay returns the stored response.
        return idempotency.execute(idempotencyKey, body, AuditRetentionPolicy.class, () -> {
            policies.validateCreate(body);
            String row = crud.insert("audit_retention_policy", crud.toColumns("audit_retention_policy", body));
            return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                    .body(crud.read(row, AuditRetentionPolicy.class));
        });
    }

    @Override
    public ResponseEntity<AuditRetentionPolicy> auditRetentionPoliciesUpdate(UUID id, String ifMatch,
                                                                             AuditRetentionPolicyUpdate body) {
        policies.validateUpdate(id, body);
        return crud.update("audit_retention_policy", AuditRetentionPolicy.class, body, ifMatch,
                Crud.params().with("id", id));
    }
}
