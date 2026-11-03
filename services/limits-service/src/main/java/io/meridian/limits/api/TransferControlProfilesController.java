package io.meridian.limits.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.limits.api.generated.TransferControlProfilesApi;
import io.meridian.limits.api.generated.model.TransferControlProfile;
import io.meridian.limits.api.generated.model.TransferControlProfileCreate;
import io.meridian.limits.api.generated.model.TransferControlProfileUpdate;
import io.meridian.limits.api.generated.model.TransferControlProfilesActivateRequest;
import io.meridian.limits.api.generated.model.TransferControlProfilesDeactivateRequest;
import io.meridian.limits.api.generated.model.TransferControlProfilesRejectRequest;
import io.meridian.limits.api.generated.model.TransferControlProfilesSubmitRequest;
import io.meridian.limits.application.TcpAdminService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** WE-LIMIT-1: cumulative (Rank-1) Transfer Control Profiles with maker-checker activation. */
@RestController
public class TransferControlProfilesController implements TransferControlProfilesApi {
    private static final String TABLE = TcpAdminService.TCP;

    private final TcpAdminService admin;
    private final CrudEngine crud;
    private final Http http;

    public TransferControlProfilesController(TcpAdminService admin, CrudEngine crud, Http http) {
        this.admin = admin;
        this.crud = crud;
        this.http = http;
    }

    @Override
    public ResponseEntity<TransferControlProfile> transferControlProfilesCreate(String idempotencyKey,
                                                                                TransferControlProfileCreate body) {
        TenantContext.require();
        admin.validate(TABLE, http.tree(body));
        return crud.create(TABLE, TransferControlProfile.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<TransferControlProfile> transferControlProfilesUpdate(UUID id, String ifMatch,
                                                                                TransferControlProfileUpdate body) {
        TenantContext.require();
        return http.ok(admin.update(TABLE, id, http.tree(body), Http.ifMatch(ifMatch)), TransferControlProfile.class);
    }

    @Override
    public ResponseEntity<TransferControlProfile> transferControlProfilesSubmit(UUID id, String idempotencyKey,
                                                                                TransferControlProfilesSubmitRequest body) {
        String comment = Http.text(http.tree(body), "comment");
        return http.once(idempotencyKey, body, TransferControlProfile.class,
                () -> http.ok(admin.submit(TABLE, TABLE, id, comment), TransferControlProfile.class));
    }

    @Override
    public ResponseEntity<TransferControlProfile> transferControlProfilesActivate(UUID id, String idempotencyKey,
                                                                                  TransferControlProfilesActivateRequest body) {
        return http.once(idempotencyKey, body, TransferControlProfile.class,
                () -> http.ok(admin.activate(TABLE, TABLE, id), TransferControlProfile.class));
    }

    @Override
    public ResponseEntity<TransferControlProfile> transferControlProfilesReject(UUID id, String idempotencyKey,
                                                                                TransferControlProfilesRejectRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, TransferControlProfile.class,
                () -> http.ok(admin.reject(TABLE, TABLE, id, reason), TransferControlProfile.class));
    }

    @Override
    public ResponseEntity<TransferControlProfile> transferControlProfilesDeactivate(UUID id, String idempotencyKey,
                                                                                    TransferControlProfilesDeactivateRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, TransferControlProfile.class,
                () -> http.ok(admin.deactivate(TABLE, id, reason), TransferControlProfile.class));
    }
}
