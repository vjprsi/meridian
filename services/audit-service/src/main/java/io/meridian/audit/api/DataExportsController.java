package io.meridian.audit.api;

import io.meridian.audit.api.generated.DataExportsApi;
import io.meridian.audit.api.generated.model.DataExportRequest;
import io.meridian.audit.api.generated.model.DataExportRequestCreate;
import io.meridian.audit.api.generated.model.DataExportsCancelRequest;
import io.meridian.audit.application.DataExportService;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Data exports under maker-checker: create (opens an approval), cancel, signed download URL. */
@RestController
public class DataExportsController implements DataExportsApi {
    private final DataExportService exports;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public DataExportsController(DataExportService exports, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.exports = exports;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<DataExportRequest> dataExportsCreate(String idempotencyKey, DataExportRequestCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, DataExportRequest.class, () -> ResponseEntity
                .status(HttpStatus.CREATED).body(crud.read(exports.create(body, idempotencyKey), DataExportRequest.class)));
    }

    @Override
    public ResponseEntity<DataExportRequest> dataExportsCancel(UUID id, String idempotencyKey,
                                                               DataExportsCancelRequest body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, DataExportRequest.class, () -> ResponseEntity
                .ok(crud.read(exports.cancel(id, body == null ? null : body.getReason()), DataExportRequest.class)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> dataExportsDownloadUrl(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok(json.convertValue(exports.downloadUrl(id), AuditEventsController.OBJECT));
    }
}
