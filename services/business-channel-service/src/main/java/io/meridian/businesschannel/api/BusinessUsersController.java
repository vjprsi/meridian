package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessUsersApi;
import io.meridian.businesschannel.api.generated.model.BusinessEntitlement;
import io.meridian.businesschannel.api.generated.model.BusinessEntitlementPage;
import io.meridian.businesschannel.api.generated.model.BusinessUser;
import io.meridian.businesschannel.api.generated.model.BusinessUserPage;
import io.meridian.businesschannel.api.generated.model.BusinessUsersCreateRequest;
import io.meridian.businesschannel.api.generated.model.BusinessUsersRemoveRequest;
import io.meridian.businesschannel.api.generated.model.BusinessUsersReplaceEntitlementsRequest;
import io.meridian.businesschannel.api.generated.model.BusinessUsersSuspendRequest;
import io.meridian.businesschannel.api.generated.model.BusinessUsersUpdateRequest;
import io.meridian.businesschannel.application.UserAdminService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Users of the business, roles, daily limits and account entitlements (changes by ADMIN users). */
@RestController
public class BusinessUsersController implements BusinessUsersApi {
    private final UserAdminService users;
    private final Http http;

    public BusinessUsersController(UserAdminService users, Http http) {
        this.users = users;
        this.http = http;
    }

    @Override
    public ResponseEntity<BusinessUserPage> businessUsersList(String status, Integer limit, String cursor) {
        return http.ok(users.list(status, limit, cursor), BusinessUserPage.class);
    }

    @Override
    public ResponseEntity<BusinessUser> businessUsersCreate(String idempotencyKey, BusinessUsersCreateRequest body) {
        return http.once(idempotencyKey, body, BusinessUser.class, () -> users.create(http.body(body)));
    }

    @Override
    public ResponseEntity<BusinessUser> businessUsersGet(UUID userId) {
        return http.ok(users.get(userId), BusinessUser.class);
    }

    @Override
    public ResponseEntity<BusinessUser> businessUsersUpdate(UUID userId, String idempotencyKey,
                                                            BusinessUsersUpdateRequest body) {
        return http.once(idempotencyKey, body, BusinessUser.class, () -> users.update(userId, http.body(body)));
    }

    @Override
    public ResponseEntity<BusinessUser> businessUsersSuspend(UUID userId, String idempotencyKey,
                                                             BusinessUsersSuspendRequest body) {
        return http.once(idempotencyKey, body, BusinessUser.class, () -> users.status(userId, "suspend", http.body(body)));
    }

    @Override
    public ResponseEntity<BusinessUser> businessUsersReactivate(UUID userId, String idempotencyKey) {
        return http.once(idempotencyKey, null, BusinessUser.class, () -> users.status(userId, "reactivate", http.body(null)));
    }

    @Override
    public ResponseEntity<BusinessUser> businessUsersRemove(UUID userId, String idempotencyKey,
                                                            BusinessUsersRemoveRequest body) {
        return http.once(idempotencyKey, body, BusinessUser.class, () -> users.status(userId, "remove", http.body(body)));
    }

    @Override
    public ResponseEntity<BusinessEntitlementPage> businessUsersEntitlements(UUID userId, Integer limit, String cursor) {
        return http.ok(users.entitlements(userId), BusinessEntitlementPage.class);
    }

    @Override
    public ResponseEntity<BusinessEntitlement> businessUsersReplaceEntitlements(UUID userId, UUID accountId,
            String idempotencyKey, BusinessUsersReplaceEntitlementsRequest body) {
        return http.once(idempotencyKey, body, BusinessEntitlement.class,
                () -> users.setEntitlement(userId, accountId, http.body(body)));
    }

    @Override
    public ResponseEntity<Void> businessUsersRemoveEntitlements(UUID userId, UUID accountId, String idempotencyKey) {
        users.removeEntitlement(userId, accountId);
        return ResponseEntity.noContent().build();
    }
}
