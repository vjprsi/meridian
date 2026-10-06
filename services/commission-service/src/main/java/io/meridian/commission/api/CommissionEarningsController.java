package io.meridian.commission.api;

import io.meridian.commission.api.generated.CommissionEarningsApi;
import io.meridian.commission.api.generated.model.CommissionEarningsAccrueRequest;
import io.meridian.commission.application.AccrualService;
import io.meridian.common.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Commission earnings (append-only): explicit accrual and summaries; list/get are generic. */
@RestController
public class CommissionEarningsController implements CommissionEarningsApi {
    private final AccrualService accruals;
    private final Actions actions;

    public CommissionEarningsController(AccrualService accruals, Actions actions) {
        this.accruals = accruals;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<Map<String, Object>> commissionEarningsAccrue(String idempotencyKey,
                                                                        CommissionEarningsAccrueRequest body) {
        return actions.objectAction(idempotencyKey, "accrue", body,
                () -> accruals.accrue(AccrualService.TxnEvent.from(actions.tree(body), "wallet_transaction_id")));
    }

    @Override
    public ResponseEntity<Map<String, Object>> commissionEarningsSummary(UUID beneficiaryAgentId, UUID beneficiaryWalletId,
                                                                         LocalDate from, LocalDate to) {
        TenantContext.require();
        return actions.object(accruals.summary(beneficiaryAgentId, beneficiaryWalletId, from, to));
    }
}
