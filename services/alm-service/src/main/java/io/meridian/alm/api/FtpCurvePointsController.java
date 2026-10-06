package io.meridian.alm.api;

import io.meridian.alm.api.generated.FtpCurvePointsApi;
import io.meridian.alm.api.generated.model.FtpCurvePoint;
import io.meridian.alm.api.generated.model.FtpCurvePointCreate;
import io.meridian.alm.api.generated.model.FtpCurvePointUpdate;
import io.meridian.alm.application.FtpService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Curve points: validated, and frozen once their curve is published. */
@RestController
public class FtpCurvePointsController implements FtpCurvePointsApi {
    private final FtpService service;
    private final CrudEngine crud;

    public FtpCurvePointsController(FtpService service, CrudEngine crud) {
        this.service = service;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<FtpCurvePoint> ftpCurvePointsCreate(String idempotencyKey, FtpCurvePointCreate body) {
        TenantContext.require();
        service.checkPoint(body.getFtpCurveId(), body.getTenorDays(), body.getTenorLabel(), body.getRate());
        return crud.create("ftp_curve_point", FtpCurvePoint.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<FtpCurvePoint> ftpCurvePointsUpdate(UUID id, String ifMatch, FtpCurvePointUpdate body) {
        TenantContext.require();
        service.checkPoint(service.curveOfPoint(id), body.getTenorDays(), body.getTenorLabel(), body.getRate());
        if (body.getFtpCurveId() != null && !body.getFtpCurveId().equals(service.curveOfPoint(id))) {
            throw io.meridian.common.error.ApiException.unprocessable("field_not_updatable",
                    "ftp_curve_id cannot be changed; delete the point and create it on the other curve");
        }
        return crud.update("ftp_curve_point", FtpCurvePoint.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Void> ftpCurvePointsDelete(UUID id) {
        TenantContext.require();
        service.checkPoint(service.curveOfPoint(id), null, null, null);
        return crud.delete("ftp_curve_point", Crud.params().with("id", id));
    }
}
