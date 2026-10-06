package io.meridian.cardclearing.api;

import io.meridian.cardclearing.api.generated.ClearingRecordsApi;
import io.meridian.cardclearing.api.generated.model.ClearingRecord;
import io.meridian.cardclearing.api.generated.model.ClearingRecordsForceMatchRequest;
import io.meridian.cardclearing.application.ClearingFileService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Clearing records (append-only) and manual force-match. */
@RestController
public class ClearingRecordsController implements ClearingRecordsApi {
    private final Actions actions;
    private final ClearingFileService files;

    ClearingRecordsController(Actions actions, ClearingFileService files) {
        this.actions = actions;
        this.files = files;
    }

    @Override
    public ResponseEntity<ClearingRecord> clearingRecordsForceMatch(UUID id, String idempotencyKey,
                                                                    ClearingRecordsForceMatchRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "force-match", body, ClearingRecord.class,
                () -> files.forceMatch(id, Http.uuid(req, "card_authorization_id"), Http.text(req, "reason")));
    }
}
