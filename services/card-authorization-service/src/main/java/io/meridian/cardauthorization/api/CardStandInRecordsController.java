package io.meridian.cardauthorization.api;

import io.meridian.cardauthorization.api.generated.CardStandInRecordsApi;
import io.meridian.cardauthorization.application.LifecycleService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Stand-in approvals and their replay to the ledger once it is available again. */
@RestController
public class CardStandInRecordsController implements CardStandInRecordsApi {
    private final LifecycleService lifecycle;
    private final Actions actions;

    public CardStandInRecordsController(LifecycleService lifecycle, Actions actions) {
        this.lifecycle = lifecycle;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<Map<String, Object>> cardStandInRecordsReplay(String idempotencyKey) {
        return actions.objectAction(idempotencyKey, "stand-in-replay", Map.of(), lifecycle::replay);
    }
}
