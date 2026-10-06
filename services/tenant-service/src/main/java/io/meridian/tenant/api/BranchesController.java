package io.meridian.tenant.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.tenant.api.generated.BranchesApi;
import io.meridian.tenant.api.generated.model.Branch;
import io.meridian.tenant.api.generated.model.BranchCreate;
import io.meridian.tenant.api.generated.model.BranchUpdate;
import io.meridian.tenant.api.generated.model.BranchesCloseRequest;
import io.meridian.tenant.application.OrganizationService;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Branches: hierarchy rules within a legal entity, status lifecycle, closure. */
@RestController
public class BranchesController implements BranchesApi {
    private final OrganizationService organization;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public BranchesController(OrganizationService organization, Idempotency idempotency, JsonMapper json) {
        this.organization = organization;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<Branch> branchesCreate(String idempotencyKey, BranchCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Branch.class,
                () -> Responses.of(HttpStatus.CREATED, organization.createBranch(body), Branch.class, json));
    }

    @Override
    public ResponseEntity<Branch> branchesUpdate(UUID id, String ifMatch, BranchUpdate body) {
        TenantContext.require();
        return organization.updateBranch(id, body, ifMatch, Branch.class);
    }

    @Override
    public ResponseEntity<Branch> branchesClose(UUID id, String idempotencyKey, BranchesCloseRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Branch.class,
                () -> Responses.of(HttpStatus.OK, organization.closeBranch(id, body.getClosedOn(), body.getReason()),
                        Branch.class, json));
    }
}
