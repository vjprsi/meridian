package io.meridian.reconciliation.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.reconciliation.api.generated.ReconFilesApi;
import io.meridian.reconciliation.api.generated.model.ReconFile;
import io.meridian.reconciliation.api.generated.model.ReconFileCreate;
import io.meridian.reconciliation.api.generated.model.ReconRecordPage;
import io.meridian.reconciliation.application.FileService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Statement files. Create takes, beyond the generated model, the file {@code content} (text) or pre-parsed
 * {@code records} (array of {value_date, direction, amount, currency, external_ref, end_to_end_id, ...}).
 */
@RestController
public class ReconFilesController implements ReconFilesApi {
    private final FileService files;
    private final Idempotency idempotency;
    private final Responses responses;
    private final CrudEngine crud;
    private final JsonMapper json;

    public ReconFilesController(FileService files, Idempotency idempotency, Responses responses, CrudEngine crud,
                                JsonMapper json) {
        this.files = files;
        this.idempotency = idempotency;
        this.responses = responses;
        this.crud = crud;
        this.json = json;
    }

    @Override
    public ResponseEntity<ReconFile> reconFilesCreate(String idempotencyKey, ReconFileCreate body) {
        TenantContext.require();
        JsonNode req = RawBody.json(json);
        return idempotency.execute(idempotencyKey, req, ReconFile.class,
                () -> responses.created(files.create(req), ReconFile.class));
    }

    @Override
    public ResponseEntity<ReconFile> reconFilesReprocess(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, responses.action("reprocess", id, null), ReconFile.class,
                () -> responses.ok(files.reprocess(id), ReconFile.class));
    }

    @Override
    public ResponseEntity<ReconRecordPage> reconFilesRecords(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        if (crud.findOne("recon_file", Map.of("id", id)).isEmpty()) {
            throw ApiException.notFound("recon file", id);
        }
        return crud.list("recon_record", ReconRecordPage.class,
                Crud.params().with("recon_file_id", id).with("limit", limit).with("cursor", cursor));
    }
}
