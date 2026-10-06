package io.meridian.treasurydeal.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.treasurydeal.api.generated.TreasurySettlementsApi;
import io.meridian.treasurydeal.api.generated.model.TreasurySettlement;
import io.meridian.treasurydeal.api.generated.model.TreasurySettlementsFailRequest;
import io.meridian.treasurydeal.api.generated.model.TreasurySettlementsMarkSettledRequest;
import io.meridian.treasurydeal.application.SettlementService;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Settlement instructions: release as payments, confirm, fail; projected cash flows for liquidity. */
@RestController
public class TreasurySettlementsController implements TreasurySettlementsApi {
    private final SettlementService service;
    private final Actions actions;

    public TreasurySettlementsController(SettlementService service, Actions actions) {
        this.service = service;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<TreasurySettlement> treasurySettlementsInstruct(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "instruct", null, TreasurySettlement.class, () -> service.instruct(id));
    }

    @Override
    public ResponseEntity<TreasurySettlement> treasurySettlementsMarkSettled(UUID id, String idempotencyKey,
                                                                            TreasurySettlementsMarkSettledRequest body) {
        return actions.action(idempotencyKey, id, "mark-settled", body, TreasurySettlement.class,
                () -> service.markSettled(id, Http.text(actions.tree(body), "reference")));
    }

    @Override
    public ResponseEntity<TreasurySettlement> treasurySettlementsFail(UUID id, String idempotencyKey,
                                                                     TreasurySettlementsFailRequest body) {
        return actions.action(idempotencyKey, id, "fail", body, TreasurySettlement.class,
                () -> service.fail(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<Map<String, Object>> treasurySettlementsProjectedFlows(LocalDate from, LocalDate to,
                                                                                UUID legalEntityId, String currency) {
        TenantContext.require();
        return actions.object(service.projectedFlows(from, to, legalEntityId, currency));
    }
}
