package io.meridian.biller.api;

import io.meridian.biller.api.generated.BillerSettlementsApi;
import io.meridian.biller.api.generated.model.BillPaymentPage;
import io.meridian.biller.api.generated.model.BillerSettlement;
import io.meridian.biller.api.generated.model.BillerSettlementCreate;
import io.meridian.biller.api.generated.model.BillerSettlementsApproveRequest;
import io.meridian.biller.api.generated.model.BillerSettlementsMarkPaidRequest;
import io.meridian.biller.api.generated.model.BillerSettlementsRejectRequest;
import io.meridian.biller.application.SettlementService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Settlement of collected payments to billers (maker-checker, payout from the collection wallet). */
@RestController
public class BillerSettlementsController implements BillerSettlementsApi {
    private final SettlementService settlements;
    private final CrudEngine crud;
    private final Actions actions;

    public BillerSettlementsController(SettlementService settlements, CrudEngine crud, Actions actions) {
        this.settlements = settlements;
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<BillerSettlement> billerSettlementsCreate(String idempotencyKey, BillerSettlementCreate body) {
        return actions.create(idempotencyKey, body, BillerSettlement.class, () -> settlements.create(actions.tree(body)));
    }

    @Override
    public ResponseEntity<BillerSettlement> billerSettlementsApprove(UUID id, String idempotencyKey,
                                                                    BillerSettlementsApproveRequest body) {
        return actions.action(idempotencyKey, id, "approve", body, BillerSettlement.class, () -> settlements.approve(id));
    }

    @Override
    public ResponseEntity<BillerSettlement> billerSettlementsReject(UUID id, String idempotencyKey,
                                                                   BillerSettlementsRejectRequest body) {
        return actions.action(idempotencyKey, id, "reject", body, BillerSettlement.class,
                () -> settlements.reject(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<BillerSettlement> billerSettlementsMarkPaid(UUID id, String idempotencyKey,
                                                                     BillerSettlementsMarkPaidRequest body) {
        return actions.action(idempotencyKey, id, "mark-paid", body, BillerSettlement.class,
                () -> settlements.markPaid(id, Http.text(actions.tree(body), "payment_reference")));
    }

    @Override
    public ResponseEntity<BillPaymentPage> billerSettlementsPayments(UUID id, Integer limit, String cursor) {
        crud.get("biller_settlement", BillerSettlement.class, Crud.params().with("id", id));
        return crud.list("bill_payment", BillPaymentPage.class,
                Crud.params().with("biller_settlement_id", id).with("limit", limit).with("cursor", cursor));
    }
}
