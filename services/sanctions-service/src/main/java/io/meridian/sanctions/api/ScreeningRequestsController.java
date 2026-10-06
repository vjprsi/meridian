package io.meridian.sanctions.api;

import io.meridian.common.error.ApiException;
import io.meridian.sanctions.api.generated.ScreeningRequestsApi;
import io.meridian.sanctions.api.generated.model.ScreeningBatch;
import io.meridian.sanctions.api.generated.model.ScreeningRequest;
import io.meridian.sanctions.api.generated.model.ScreeningRequestCreate;
import io.meridian.sanctions.api.generated.model.ScreeningRequestsBatchRequest;
import io.meridian.sanctions.api.generated.model.ScreeningRequestsScreenPaymentRequest;
import io.meridian.sanctions.api.generated.model.ScreeningResult;
import io.meridian.sanctions.application.BatchService;
import io.meridian.sanctions.application.ScreeningService;
import io.meridian.sanctions.infrastructure.Db;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Screening of subjects (create), payments (synchronous) and batches (asynchronous). */
@RestController
public class ScreeningRequestsController implements ScreeningRequestsApi {
    private final ScreeningService screening;
    private final BatchService batches;
    private final Http http;

    public ScreeningRequestsController(ScreeningService screening, BatchService batches, Http http) {
        this.screening = screening;
        this.batches = batches;
        this.http = http;
    }

    /** Screens synchronously; the response carries status and screening_result_id. */
    @Override
    public ResponseEntity<ScreeningRequest> screeningRequestsCreate(String idempotencyKey, ScreeningRequestCreate body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, ScreeningRequest.class, () -> http.respond(HttpStatus.CREATED,
                screening.screenSubject(req).get("request").toString(), ScreeningRequest.class));
    }

    @Override
    public ResponseEntity<ScreeningResult> screeningRequestsScreenPayment(String idempotencyKey,
                                                                          ScreeningRequestsScreenPaymentRequest body) {
        JsonNode req = http.tree(body);
        UUID paymentId = Db.uuid(req, "payment_id");
        if (paymentId == null) {
            throw ApiException.unprocessable("payment_id_required", "payment_id is required");
        }
        return http.once(idempotencyKey, body, ScreeningResult.class, () -> http.ok(screening.screenPayment(paymentId,
                req.get("payload"), Db.dec(req, "threshold"), Http.text(req, "correlation_id")).toString(), ScreeningResult.class));
    }

    @Override
    public ResponseEntity<ScreeningBatch> screeningRequestsBatch(String idempotencyKey, ScreeningRequestsBatchRequest body) {
        JsonNode req = http.tree(body);
        return http.once(idempotencyKey, body, ScreeningBatch.class, () -> http.ok(batches.submit(req.get("subjects"),
                Http.text(req, "trigger"), Db.dec(req, "threshold")), ScreeningBatch.class));
    }
}
