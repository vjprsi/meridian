package io.meridian.biller.api;

import io.meridian.biller.api.generated.BillPaymentsApi;
import io.meridian.biller.api.generated.model.BillPayment;
import io.meridian.biller.api.generated.model.BillPaymentCreate;
import io.meridian.biller.api.generated.model.BillPaymentsReverseRequest;
import io.meridian.biller.application.PaymentService;
import io.meridian.common.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Bill payments from wallets: reserve, pay at the biller, confirm / cancel, retry and reverse. */
@RestController
public class BillPaymentsController implements BillPaymentsApi {
    private final PaymentService payments;
    private final Actions actions;

    public BillPaymentsController(PaymentService payments, Actions actions) {
        this.payments = payments;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<BillPayment> billPaymentsCreate(String idempotencyKey, BillPaymentCreate body) {
        return actions.create(idempotencyKey, body, BillPayment.class, () -> payments.payBill(actions.tree(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<BillPayment> billPaymentsRetry(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "retry", null, BillPayment.class,
                () -> payments.retry(PaymentService.Kind.BILL, id));
    }

    @Override
    public ResponseEntity<BillPayment> billPaymentsReverse(UUID id, String idempotencyKey, BillPaymentsReverseRequest body) {
        return actions.action(idempotencyKey, id, "reverse", body, BillPayment.class,
                () -> payments.reverse(id, Http.text(actions.tree(body), "reason")));
    }

    @Override
    public ResponseEntity<String> billPaymentsReceipt(UUID id) {
        TenantContext.require();
        return Actions.text(payments.receipt(id), "receipt-" + id + ".txt");
    }
}
