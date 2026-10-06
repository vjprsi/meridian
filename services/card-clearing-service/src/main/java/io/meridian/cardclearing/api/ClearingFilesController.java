package io.meridian.cardclearing.api;

import io.meridian.cardclearing.api.generated.ClearingFilesApi;
import io.meridian.cardclearing.api.generated.model.ClearingFile;
import io.meridian.cardclearing.api.generated.model.ClearingFileCreate;
import io.meridian.cardclearing.api.generated.model.ClearingRecordPage;
import io.meridian.cardclearing.application.ClearingFileService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Clearing files: upload (parse, match, post), reprocess and the file's records. */
@RestController
public class ClearingFilesController implements ClearingFilesApi {
    private final Actions actions;
    private final ClearingFileService files;
    private final CrudEngine crud;

    ClearingFilesController(Actions actions, ClearingFileService files, CrudEngine crud) {
        this.actions = actions;
        this.files = files;
        this.crud = crud;
    }

    @Override
    public ResponseEntity<ClearingFile> clearingFilesCreate(String idempotencyKey, ClearingFileCreate body) {
        return actions.create(idempotencyKey, body, ClearingFile.class, () -> files.upload(actions.tree(body)));
    }

    @Override
    public ResponseEntity<ClearingFile> clearingFilesReprocess(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "reprocess", null, ClearingFile.class, () -> files.reprocess(id));
    }

    @Override
    public ResponseEntity<ClearingRecordPage> clearingFilesRecords(UUID id, Integer limit, String cursor) {
        crud.get("clearing_file", ClearingFile.class, Crud.params().with("id", id));
        return crud.list("clearing_record", ClearingRecordPage.class,
                Crud.params().with("clearing_file_id", id).with("limit", limit).with("cursor", cursor));
    }
}
