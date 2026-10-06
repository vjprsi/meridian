package io.meridian.sanctions.api;

import io.meridian.sanctions.api.generated.ScreeningResultsApi;
import io.meridian.sanctions.api.generated.model.ScreeningMatchPage;
import io.meridian.sanctions.api.generated.model.ScreeningResult;
import io.meridian.sanctions.api.generated.model.ScreeningResultsReviewRequest;
import io.meridian.sanctions.application.ScreeningService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Screening results: candidate matches, compliance review and rescreening. */
@RestController
public class ScreeningResultsController implements ScreeningResultsApi {
    private final ScreeningService screening;
    private final CrudEngine crud;
    private final Http http;

    public ScreeningResultsController(ScreeningService screening, CrudEngine crud, Http http) {
        this.screening = screening;
        this.crud = crud;
        this.http = http;
    }

    @Override
    public ResponseEntity<ScreeningMatchPage> screeningResultsMatches(UUID id, Integer limit, String cursor) {
        crud.get("screening_result", Object.class, Crud.params().with("id", id));
        return crud.list("screening_match", ScreeningMatchPage.class,
                Crud.params().with("screening_result_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<ScreeningResult> screeningResultsReview(UUID id, String idempotencyKey, ScreeningResultsReviewRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, ScreeningResult.class, () -> http.ok(screening.review(id,
                Http.text(req, "status"), Http.text(req, "decision_reason"),
                req.path("whitelist_false_positives").asBoolean(false)).toString(), ScreeningResult.class));
    }

    @Override
    public ResponseEntity<ScreeningResult> screeningResultsRescreen(UUID id, String idempotencyKey) {
        return http.once(idempotencyKey, null, ScreeningResult.class,
                () -> http.ok(screening.rescreen(id).toString(), ScreeningResult.class));
    }
}
