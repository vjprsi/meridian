package io.meridian.agentchannel.api;

import io.meridian.agentchannel.api.generated.AgentMeBillersApi;
import io.meridian.agentchannel.api.generated.model.AgentMeBillersValidateReferenceRequest;
import io.meridian.agentchannel.api.generated.model.BillInquiry;
import io.meridian.agentchannel.api.generated.model.BillerPage;
import io.meridian.agentchannel.api.generated.model.BillerProductPage;
import io.meridian.agentchannel.application.MoneyService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Biller catalogue (reference data) and account reference validation. */
@RestController
public class AgentMeBillersController implements AgentMeBillersApi {
    private final MoneyService money;
    private final Http http;

    public AgentMeBillersController(MoneyService money, Http http) {
        this.money = money;
        this.http = http;
    }

    @Override
    public ResponseEntity<BillerPage> agentMeBillersList(String category, Integer limit, String cursor) {
        return http.ok(money.billers(category, limit, cursor), BillerPage.class);
    }

    @Override
    public ResponseEntity<BillerProductPage> agentMeBillersProducts(UUID billerId, Integer limit, String cursor) {
        return http.ok(money.billerProducts(billerId, limit, cursor), BillerProductPage.class);
    }

    @Override
    public ResponseEntity<BillInquiry> agentMeBillersValidateReference(UUID billerId, String idempotencyKey,
                                                                      AgentMeBillersValidateReferenceRequest body) {
        return http.ok(money.validateReference(billerId, http.body(body), idempotencyKey), BillInquiry.class);
    }
}
