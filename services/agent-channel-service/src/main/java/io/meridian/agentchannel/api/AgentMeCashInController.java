package io.meridian.agentchannel.api;

import io.meridian.agentchannel.api.generated.AgentMeCashInApi;
import io.meridian.agentchannel.api.generated.model.AgentMeCashInCreateRequest;
import io.meridian.agentchannel.api.generated.model.WalletTransaction;
import io.meridian.agentchannel.application.MoneyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Cash-in from the agent's float wallet. */
@RestController
public class AgentMeCashInController implements AgentMeCashInApi {
    private final MoneyService money;
    private final Http http;

    public AgentMeCashInController(MoneyService money, Http http) {
        this.money = money;
        this.http = http;
    }

    @Override
    public ResponseEntity<WalletTransaction> agentMeCashInCreate(String idempotencyKey, AgentMeCashInCreateRequest body) {
        return http.ok(money.cashIn(http.body(body), idempotencyKey), WalletTransaction.class);
    }
}
