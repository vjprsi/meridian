package io.meridian.alm.api;

import io.meridian.alm.api.generated.FtpCurvesApi;
import io.meridian.alm.api.generated.model.FtpCurve;
import io.meridian.alm.api.generated.model.FtpCurvePointPage;
import io.meridian.alm.api.generated.model.FtpCurveUpdate;
import io.meridian.alm.api.generated.model.FtpCurvesPublishRequest;
import io.meridian.alm.api.generated.model.FtpCurvesRejectRequest;
import io.meridian.alm.api.generated.model.FtpCurvesSubmitRequest;
import io.meridian.alm.application.FtpService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** FTP curves: frozen once published (maker-checker), points and interpolation. */
@RestController
public class FtpCurvesController implements FtpCurvesApi {
    private final FtpService service;
    private final Actions actions;
    private final CrudEngine crud;

    public FtpCurvesController(FtpService service, Actions actions, CrudEngine crud) {
        this.service = service;
        this.actions = actions;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<FtpCurve> ftpCurvesUpdate(UUID id, String ifMatch, FtpCurveUpdate body) {
        TenantContext.require();
        service.checkCurveEditable(id);
        return crud.update("ftp_curve", FtpCurve.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<FtpCurve> ftpCurvesPublish(UUID id, String idempotencyKey, FtpCurvesPublishRequest body) {
        return actions.action(idempotencyKey, id, "publish", body, FtpCurve.class,
                () -> service.publish(id, Http.text(actions.tree(body), "comment")));
    }

    @Override
    public ResponseEntity<FtpCurve> ftpCurvesSubmit(UUID id, String idempotencyKey, FtpCurvesSubmitRequest body) {
        return actions.action(idempotencyKey, id, "submit", body, FtpCurve.class,
                () -> service.submit(id, Http.text(actions.tree(body), "comment")));
    }

    @Override
    public ResponseEntity<FtpCurve> ftpCurvesReject(UUID id, String idempotencyKey, FtpCurvesRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, FtpCurve.class,
                () -> service.reject(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<FtpCurvePointPage> ftpCurvesPoints(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        crud.get("ftp_curve", FtpCurve.class, Crud.params().with("id", id)); // 404 for an unknown curve
        return crud.list("ftp_curve_point", FtpCurvePointPage.class,
                Crud.params().with("ftp_curve_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<Map<String, Object>> ftpCurvesRate(UUID id, Integer tenorDays) {
        TenantContext.require();
        return actions.object(service.rate(id, tenorDays));
    }
}
