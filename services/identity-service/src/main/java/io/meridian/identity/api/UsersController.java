package io.meridian.identity.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.identity.api.generated.UsersApi;
import io.meridian.identity.api.generated.model.AppUser;
import io.meridian.identity.api.generated.model.AppUserCreate;
import io.meridian.identity.api.generated.model.AppUserUpdate;
import io.meridian.identity.api.generated.model.ApprovalRequest;
import io.meridian.identity.api.generated.model.UserRolePage;
import io.meridian.identity.api.generated.model.UsersDeactivateRequest;
import io.meridian.identity.api.generated.model.UsersLockRequest;
import io.meridian.identity.api.generated.model.UsersResetMfaRequest;
import io.meridian.identity.api.generated.model.UsersUnlockRequest;
import io.meridian.identity.application.AuthorizationService;
import io.meridian.identity.application.UserService;
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

/** Staff users: lifecycle actions, role assignments, effective permissions and the calling user. */
@RestController
public class UsersController implements UsersApi {
    private final UserService users;
    private final AuthorizationService authz;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public UsersController(UserService users, AuthorizationService authz, CrudEngine crud, Idempotency idempotency,
                           JsonMapper json) {
        this.users = users;
        this.authz = authz;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<AppUser> usersCreate(String idempotencyKey, AppUserCreate body) {
        return idempotency.execute(idempotencyKey, body, AppUser.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(crud.read(users.create(body), AppUser.class)));
    }

    @Override
    public ResponseEntity<AppUser> usersUpdate(UUID id, String ifMatch, AppUserUpdate body) {
        users.checkUpdate(body);
        return UsersApi.super.usersUpdate(id, ifMatch, body);
    }

    @Override
    public ResponseEntity<AppUser> usersInvite(UUID id, String idempotencyKey) {
        return action(idempotencyKey, Map.of("op", "invite", "id", id), () -> users.invite(id));
    }

    @Override
    public ResponseEntity<AppUser> usersLock(UUID id, String idempotencyKey, UsersLockRequest body) {
        return action(idempotencyKey, request("lock", id, body), () -> users.lock(id, reason(body)));
    }

    @Override
    public ResponseEntity<AppUser> usersUnlock(UUID id, String idempotencyKey, UsersUnlockRequest body) {
        return action(idempotencyKey, request("unlock", id, body), () -> users.unlock(id, reason(body)));
    }

    @Override
    public ResponseEntity<AppUser> usersDeactivate(UUID id, String idempotencyKey, UsersDeactivateRequest body) {
        return action(idempotencyKey, request("deactivate", id, body), () -> users.deactivate(id, reason(body)));
    }

    @Override
    public ResponseEntity<ApprovalRequest> usersResetMfa(UUID id, String idempotencyKey, UsersResetMfaRequest body) {
        return idempotency.execute(idempotencyKey, request("reset-mfa", id, body), ApprovalRequest.class,
                () -> ResponseEntity.ok(json.treeToValue(users.requestMfaReset(id, reason(body), idempotencyKey),
                        ApprovalRequest.class)));
    }

    @Override
    public ResponseEntity<UserRolePage> usersRoles(UUID id, Integer limit, String cursor) {
        users.require(id);
        return crud.list("user_role", UserRolePage.class,
                Crud.params().with("user_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<Void> usersEffectivePermissions(UUID id) {
        TenantContext.require();
        return Responses.body(ResponseEntity.ok(authz.effectivePermissions(id)));
    }

    @Override
    public ResponseEntity<AppUser> usersMe() {
        return ResponseEntity.ok(crud.read(users.me(), AppUser.class));
    }

    private ResponseEntity<AppUser> action(String key, Object request, java.util.function.Supplier<String> op) {
        return idempotency.execute(key, request, AppUser.class, () -> ResponseEntity.ok(crud.read(op.get(), AppUser.class)));
    }

    private Map<String, Object> request(String op, UUID id, Object body) {
        return Map.of("op", op, "id", id, "body", body == null ? Map.of() : json.valueToTree(body));
    }

    private String reason(Object body) {
        JsonNode n = body == null ? json.createObjectNode() : json.valueToTree(body);
        return n.hasNonNull("reason") ? n.get("reason").asString() : null;
    }
}
