package io.meridian.identity.api;

import io.meridian.identity.api.generated.UserRolesApi;
import io.meridian.identity.api.generated.model.ApprovalRequest;
import io.meridian.identity.api.generated.model.UserRole;
import io.meridian.identity.api.generated.model.UserRoleCreate;
import io.meridian.identity.api.generated.model.UserRolesElevateRequest;
import io.meridian.identity.application.UserRoleService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Role assignments (segregation of duties) and just-in-time elevation requests. */
@RestController
public class UserRolesController implements UserRolesApi {
    private final UserRoleService userRoles;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public UserRolesController(UserRoleService userRoles, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.userRoles = userRoles;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<UserRole> userRolesCreate(String idempotencyKey, UserRoleCreate body) {
        return idempotency.execute(idempotencyKey, body, UserRole.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(crud.read(userRoles.assign(body), UserRole.class)));
    }

    @Override
    public ResponseEntity<Void> userRolesDelete(UUID id) {
        userRoles.revoke(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ApprovalRequest> userRolesElevate(String idempotencyKey, UserRolesElevateRequest body) {
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, body, ApprovalRequest.class, () -> ResponseEntity.ok(json.treeToValue(
                userRoles.requestElevation(UUID.fromString(req.get("role_id").asString()),
                        req.hasNonNull("duration_minutes") ? req.get("duration_minutes").asInt() : null,
                        req.path("justification").asString(null), idempotencyKey),
                ApprovalRequest.class)));
    }
}
