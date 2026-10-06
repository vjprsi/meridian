package io.meridian.card.api;

import io.meridian.card.api.generated.CardStatementCyclesApi;
import io.meridian.card.api.generated.model.CardStatementCycle;
import io.meridian.card.application.StatementService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Credit card statement cycles. */
@RestController
public class CardStatementCyclesController implements CardStatementCyclesApi {
    private final StatementService statements;
    private final Actions actions;

    public CardStatementCyclesController(StatementService statements, Actions actions) {
        this.statements = statements;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<CardStatementCycle> cardStatementCyclesClose(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "close", null, CardStatementCycle.class, () -> statements.close(id));
    }
}
