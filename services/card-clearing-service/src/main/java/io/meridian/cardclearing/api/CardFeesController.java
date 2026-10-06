package io.meridian.cardclearing.api;

import io.meridian.cardclearing.api.generated.CardFeesApi;
import io.meridian.cardclearing.api.generated.model.CardFee;
import io.meridian.cardclearing.api.generated.model.CardFeeCreate;
import io.meridian.cardclearing.api.generated.model.CardFeesWaiveRequest;
import io.meridian.cardclearing.application.FeeService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Card fees: charge with a ledger posting, waive with a compensating entry. */
@RestController
public class CardFeesController implements CardFeesApi {
    private final Actions actions;
    private final FeeService fees;

    CardFeesController(Actions actions, FeeService fees) {
        this.actions = actions;
        this.fees = fees;
    }

    @Override
    public ResponseEntity<CardFee> cardFeesCreate(String idempotencyKey, CardFeeCreate body) {
        return actions.create(idempotencyKey, body, CardFee.class, () -> fees.charge(actions.tree(body)));
    }

    @Override
    public ResponseEntity<CardFee> cardFeesWaive(UUID id, String idempotencyKey, CardFeesWaiveRequest body) {
        String reason = Http.text(actions.tree(body), "reason");
        return actions.action(idempotencyKey, id, "waive", body, CardFee.class, () -> fees.waive(id, reason));
    }
}
