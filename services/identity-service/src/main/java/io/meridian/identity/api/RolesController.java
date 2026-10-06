package io.meridian.identity.api;

import io.meridian.identity.api.generated.RolesApi;
import io.meridian.identity.api.generated.model.ApprovalRequest;
import io.meridian.identity.api.generated.model.Role;
import io.meridian.identity.api.generated.model.RoleCreate;
import io.meridian.identity.api.generated.model.RolePermissionPage;
import io.meridian.identity.api.generated.model.RoleUpdate;
import io.meridian.identity.api.generated.model.RolesCloneRequest;
import io.meridian.identity.api.generated.model.RolesPermissionsRequest;
import io.meridian.identity.application.RoleService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Roles: SYSTEM roles are immutable; permission grants are maker-checker. */
@RestController
public class RolesController implements RolesApi {
    private final RoleService roles;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public RolesController(RoleService roles, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.roles = roles;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<Role> rolesCreate(String idempotencyKey, RoleCreate body) {
        return idempotency.execute(idempotencyKey, body, Role.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(crud.read(roles.create(body), Role.class)));
    }

    @Override
    public ResponseEntity<Role> rolesUpdate(UUID id, String ifMatch, RoleUpdate body) {
        return roles.update(id, ifMatch, body, Role.class);
    }

    @Override
    public ResponseEntity<Void> rolesDelete(UUID id) {
        roles.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Role> rolesClone(UUID id, String idempotencyKey, RolesCloneRequest body) {
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, Map.of("op", "clone", "id", id, "body", req), Role.class,
                () -> ResponseEntity.ok(crud.read(roles.cloneRole(id, req.path("code").asString(null),
                        req.path("name").asString(null)), Role.class)));
    }

    @Override
    public ResponseEntity<RolePermissionPage> rolesPermissions(UUID id, Integer limit, String cursor) {
        roles.require(id);
        return crud.list("role_permission", RolePermissionPage.class,
                Crud.params().with("role_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<ApprovalRequest> rolesAddPermissions(UUID id, String idempotencyKey,
                                                               RolesPermissionsRequest body) {
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, Map.of("op", "add-permissions", "id", id, "body", req),
                ApprovalRequest.class,
                () -> ResponseEntity.ok(json.treeToValue(
                        roles.requestGrant(id, req.get("permission_ids"), req.get("conditions"), idempotencyKey),
                        ApprovalRequest.class)));
    }
}
