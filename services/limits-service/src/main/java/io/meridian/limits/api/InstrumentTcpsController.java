package io.meridian.limits.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.limits.api.generated.InstrumentTcpsApi;
import io.meridian.limits.api.generated.model.InstrumentTcp;
import io.meridian.limits.api.generated.model.InstrumentTcpCreate;
import io.meridian.limits.api.generated.model.InstrumentTcpUpdate;
import io.meridian.limits.api.generated.model.InstrumentTcpsActivateRequest;
import io.meridian.limits.api.generated.model.InstrumentTcpsDeactivateRequest;
import io.meridian.limits.api.generated.model.InstrumentTcpsRejectRequest;
import io.meridian.limits.api.generated.model.InstrumentTcpsSubmitRequest;
import io.meridian.limits.application.TcpAdminService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** WE-LIMIT-1: instrument/grade (Rank-2) Transfer Control Profiles with maker-checker activation. */
@RestController
public class InstrumentTcpsController implements InstrumentTcpsApi {
    private static final String TABLE = TcpAdminService.INSTRUMENT_TCP;

    private final TcpAdminService admin;
    private final CrudEngine crud;
    private final Http http;

    public InstrumentTcpsController(TcpAdminService admin, CrudEngine crud, Http http) {
        this.admin = admin;
        this.crud = crud;
        this.http = http;
    }

    @Override
    public ResponseEntity<InstrumentTcp> instrumentTcpsCreate(String idempotencyKey, InstrumentTcpCreate body) {
        TenantContext.require();
        admin.validate(TABLE, http.tree(body));
        return crud.create(TABLE, InstrumentTcp.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<InstrumentTcp> instrumentTcpsUpdate(UUID id, String ifMatch, InstrumentTcpUpdate body) {
        TenantContext.require();
        return http.ok(admin.update(TABLE, id, http.tree(body), Http.ifMatch(ifMatch)), InstrumentTcp.class);
    }

    @Override
    public ResponseEntity<InstrumentTcp> instrumentTcpsSubmit(UUID id, String idempotencyKey, InstrumentTcpsSubmitRequest body) {
        String comment = Http.text(http.tree(body), "comment");
        return http.once(idempotencyKey, body, InstrumentTcp.class,
                () -> http.ok(admin.submit(TABLE, TABLE, id, comment), InstrumentTcp.class));
    }

    @Override
    public ResponseEntity<InstrumentTcp> instrumentTcpsActivate(UUID id, String idempotencyKey,
                                                                InstrumentTcpsActivateRequest body) {
        return http.once(idempotencyKey, body, InstrumentTcp.class,
                () -> http.ok(admin.activate(TABLE, TABLE, id), InstrumentTcp.class));
    }

    @Override
    public ResponseEntity<InstrumentTcp> instrumentTcpsReject(UUID id, String idempotencyKey, InstrumentTcpsRejectRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, InstrumentTcp.class,
                () -> http.ok(admin.reject(TABLE, TABLE, id, reason), InstrumentTcp.class));
    }

    @Override
    public ResponseEntity<InstrumentTcp> instrumentTcpsDeactivate(UUID id, String idempotencyKey,
                                                                  InstrumentTcpsDeactivateRequest body) {
        String reason = Http.text(http.tree(body), "reason");
        return http.once(idempotencyKey, body, InstrumentTcp.class,
                () -> http.ok(admin.deactivate(TABLE, id, reason), InstrumentTcp.class));
    }
}
