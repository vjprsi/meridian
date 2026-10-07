package io.meridian.agentchannel.api;

import io.meridian.agentchannel.api.generated.AgentMeBillPaymentsApi;
import io.meridian.agentchannel.api.generated.model.AgentMeBillPaymentsCreateRequest;
import io.meridian.agentchannel.api.generated.model.BillPayment;
import io.meridian.agentchannel.api.generated.model.BillPaymentPage;
import io.meridian.agentchannel.application.MoneyService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Bill payments from the agent's float wallet. */
@RestController
public class AgentMeBillPaymentsController implements AgentMeBillPaymentsApi {
    private final MoneyService money;
    private final Http http;

    public AgentMeBillPaymentsController(MoneyService money, Http http) {
        this.money = money;
        this.http = http;
    }

    @Override
    public ResponseEntity<BillPaymentPage> agentMeBillPaymentsList(String idempotencyKey, Integer limit, String cursor) {
        return http.ok(money.billPayments(idempotencyKey, limit, cursor), BillPaymentPage.class);
    }

    @Override
    public ResponseEntity<BillPayment> agentMeBillPaymentsCreate(String idempotencyKey,
                                                                AgentMeBillPaymentsCreateRequest body) {
        return http.ok(money.payBill(http.body(body), idempotencyKey), BillPayment.class);
    }

    @Override
    public ResponseEntity<BillPayment> agentMeBillPaymentsGet(UUID billPaymentId) {
        return http.ok(money.billPayment(billPaymentId), BillPayment.class);
    }

    @Override
    public ResponseEntity<String> agentMeBillPaymentsReceipt(UUID billPaymentId) {
        return Http.text(money.billReceipt(billPaymentId), "bill-receipt-" + billPaymentId + ".txt");
    }
}
