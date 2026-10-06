package io.meridian.gl.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.gl.api.generated.ErpExportsApi;
import io.meridian.gl.api.generated.model.ErpExport;
import io.meridian.gl.api.generated.model.ErpExportCreate;
import io.meridian.gl.application.ErpExportService;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** ERP exports: generate the CSV of a closed period; download returns the file (text/csv). */
@RestController
public class ErpExportsController implements ErpExportsApi {
    private final ErpExportService exports;
    private final Idempotency idempotency;
    private final Responses responses;

    public ErpExportsController(ErpExportService exports, Idempotency idempotency, Responses responses) {
        this.exports = exports;
        this.idempotency = idempotency;
        this.responses = responses;
    }

    @Override
    public ResponseEntity<ErpExport> erpExportsCreate(String idempotencyKey, ErpExportCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, ErpExport.class,
                () -> responses.created(exports.create(body), ErpExport.class));
    }

    @Override
    public ResponseEntity<String> erpExportsDownload(UUID id) {
        TenantContext.require();
        ErpExportService.Download d = exports.download(id);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.fileName() + "\"")
                .header("X-Checksum-SHA256", d.checksum())
                .body(d.content());
    }
}
