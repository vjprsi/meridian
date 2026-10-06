package io.meridian.kyc.api;

import io.meridian.kyc.api.generated.KycScreeningsApi;
import io.meridian.kyc.api.generated.model.KycScreening;
import io.meridian.kyc.api.generated.model.KycScreeningCreate;
import io.meridian.kyc.api.generated.model.KycScreeningsDispositionRequest;
import io.meridian.kyc.application.KycSupport;
import io.meridian.kyc.application.ScreeningService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Screening results and their (maker-checker) disposition. */
@RestController
public class KycScreeningsController implements KycScreeningsApi {
    private final ScreeningService screenings;
    private final Actions actions;
    private final KycSupport kyc;

    public KycScreeningsController(ScreeningService screenings, Actions actions, KycSupport kyc) {
        this.screenings = screenings;
        this.actions = actions;
        this.kyc = kyc;
    }

    @Override
    public ResponseEntity<KycScreening> kycScreeningsCreate(String idempotencyKey, KycScreeningCreate body) {
        return actions.created(idempotencyKey, "screening.create", body, KycScreening.class, () -> screenings.create(body));
    }

    @Override
    public ResponseEntity<KycScreening> kycScreeningsDisposition(UUID id, String idempotencyKey,
                                                                 KycScreeningsDispositionRequest body) {
        JsonNode req = kyc.tree(body);
        return actions.ok(idempotencyKey, "screening.disposition", id, body, KycScreening.class,
                () -> screenings.disposition(id, KycSupport.text(req, "status"), KycSupport.text(req, "review_comment")));
    }
}
