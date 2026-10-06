package io.meridian.identity.api;

import io.meridian.identity.api.generated.RolePermissionsApi;
import io.meridian.identity.application.RoleService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Role permission grants: removal is immediate (reducing access needs no approval) and publishes role.changed. */
@RestController
public class RolePermissionsController implements RolePermissionsApi {
    private final RoleService roles;

    public RolePermissionsController(RoleService roles) {
        this.roles = roles;
    }

    @Override
    public ResponseEntity<Void> rolePermissionsDelete(UUID id) {
        roles.deleteRolePermission(id);
        return ResponseEntity.noContent().build();
    }
}
