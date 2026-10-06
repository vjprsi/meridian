package io.meridian.cardclearing.api;

import io.meridian.cardclearing.api.generated.CardSettlementsApi;
import io.meridian.cardclearing.api.generated.model.CardSettlementRecord;
import io.meridian.cardclearing.api.generated.model.CardSettlementsConfirmRequest;
import io.meridian.cardclearing.application.SettlementService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Scheme settlement positions (calculated by file intake) and their confirmation. */
@RestController
public class CardSettlementsController implements CardSettlementsApi {
    private final Actions actions;
    private final SettlementService settlements;

    CardSettlementsController(Actions actions, SettlementService settlements) {
        this.actions = actions;
        this.settlements = settlements;
    }

    @Override
    public ResponseEntity<CardSettlementRecord> cardSettlementsConfirm(UUID id, String idempotencyKey,
                                                                      CardSettlementsConfirmRequest body) {
        String ref = Http.text(actions.tree(body), "recon_reference");
        return actions.action(idempotencyKey, id, "confirm", body, CardSettlementRecord.class,
                () -> settlements.confirm(id, ref));
    }
}
