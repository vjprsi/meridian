package io.meridian.merchantchannel.api;

import io.meridian.merchantchannel.api.generated.MerchantAppStaffApi;
import io.meridian.merchantchannel.api.generated.model.MerchantAppStaffCreateRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantAppStaffRemoveRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantAppStaffReplaceStoresRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantAppStaffSuspendRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantAppStaffUpdateRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantUser;
import io.meridian.merchantchannel.api.generated.model.MerchantUserPage;
import io.meridian.merchantchannel.api.generated.model.MerchantUserStorePage;
import io.meridian.merchantchannel.application.StaffService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Users of the merchant, roles and store scopes (changes by OWNER users). */
@RestController
public class MerchantAppStaffController implements MerchantAppStaffApi {
    private final StaffService staff;
    private final Http http;

    public MerchantAppStaffController(StaffService staff, Http http) {
        this.staff = staff;
        this.http = http;
    }

    @Override
    public ResponseEntity<MerchantUserPage> merchantAppStaffList(String status, Integer limit, String cursor) {
        return http.ok(staff.list(status, limit, cursor), MerchantUserPage.class);
    }

    @Override
    public ResponseEntity<MerchantUser> merchantAppStaffCreate(String idempotencyKey, MerchantAppStaffCreateRequest body) {
        return http.once(idempotencyKey, body, MerchantUser.class, () -> staff.create(http.body(body)));
    }

    @Override
    public ResponseEntity<MerchantUser> merchantAppStaffGet(UUID userId) {
        return http.ok(staff.get(userId), MerchantUser.class);
    }

    @Override
    public ResponseEntity<MerchantUser> merchantAppStaffUpdate(UUID userId, String idempotencyKey,
                                                               MerchantAppStaffUpdateRequest body) {
        return http.once(idempotencyKey, body, MerchantUser.class, () -> staff.update(userId, http.body(body)));
    }

    @Override
    public ResponseEntity<MerchantUserStorePage> merchantAppStaffStores(UUID userId, Integer limit, String cursor) {
        return http.ok(staff.stores(userId), MerchantUserStorePage.class);
    }

    @Override
    public ResponseEntity<MerchantUserStorePage> merchantAppStaffReplaceStores(UUID userId, String idempotencyKey,
            MerchantAppStaffReplaceStoresRequest body, Integer limit, String cursor) {
        return http.once(idempotencyKey, body, MerchantUserStorePage.class,
                () -> staff.replaceStores(userId, http.body(body)));
    }

    @Override
    public ResponseEntity<MerchantUser> merchantAppStaffSuspend(UUID userId, String idempotencyKey,
                                                                MerchantAppStaffSuspendRequest body) {
        return http.once(idempotencyKey, body, MerchantUser.class, () -> staff.status(userId, "suspend", http.body(body)));
    }

    @Override
    public ResponseEntity<MerchantUser> merchantAppStaffReactivate(UUID userId, String idempotencyKey) {
        return http.once(idempotencyKey, null, MerchantUser.class,
                () -> staff.status(userId, "reactivate", http.body(null)));
    }

    @Override
    public ResponseEntity<MerchantUser> merchantAppStaffRemove(UUID userId, String idempotencyKey,
                                                               MerchantAppStaffRemoveRequest body) {
        return http.once(idempotencyKey, body, MerchantUser.class, () -> staff.status(userId, "remove", http.body(body)));
    }
}
