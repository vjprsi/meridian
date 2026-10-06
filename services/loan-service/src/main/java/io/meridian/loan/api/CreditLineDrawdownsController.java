package io.meridian.loan.api;

import io.meridian.loan.api.generated.CreditLineDrawdownsApi;
import io.meridian.loan.api.generated.model.CreditLineDrawdown;
import io.meridian.loan.api.generated.model.CreditLineDrawdownsReverseRequest;
import io.meridian.loan.application.CreditLineService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Credit-line draws (list/get via generic CRUD) and their reversal. */
@RestController
public class CreditLineDrawdownsController implements CreditLineDrawdownsApi {
    private final CreditLineService lines;
    private final Actions actions;

    public CreditLineDrawdownsController(CreditLineService lines, Actions actions) {
        this.lines = lines;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<CreditLineDrawdown> creditLineDrawdownsReverse(UUID id, String idempotencyKey,
                                                                         CreditLineDrawdownsReverseRequest body) {
        return actions.action(idempotencyKey, id, "reverse", body, CreditLineDrawdown.class,
                () -> lines.reverseDraw(id, Http.text(actions.tree(body), "reason")));
    }
}
