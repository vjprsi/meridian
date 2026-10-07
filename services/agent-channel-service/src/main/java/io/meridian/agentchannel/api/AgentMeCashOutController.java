package io.meridian.agentchannel.api;

import io.meridian.agentchannel.api.generated.AgentMeCashOutApi;
import io.meridian.agentchannel.api.generated.model.AgentMeCashOutCancelRequest;
import io.meridian.agentchannel.api.generated.model.AgentMeCashOutConfirmRequest;
import io.meridian.agentchannel.api.generated.model.AgentMeCashOutCreateRequest;
import io.meridian.agentchannel.api.generated.model.WalletTransaction;
import io.meridian.agentchannel.application.MoneyService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Cash-out at the agent: start, customer confirmation, cancel. */
@RestController
public class AgentMeCashOutController implements AgentMeCashOutApi {
    private final MoneyService money;
    private final Http http;

    public AgentMeCashOutController(MoneyService money, Http http) {
        this.money = money;
        this.http = http;
    }

    @Override
    public ResponseEntity<WalletTransaction> agentMeCashOutCreate(String idempotencyKey, AgentMeCashOutCreateRequest body) {
        return http.ok(money.cashOut(http.body(body), idempotencyKey), WalletTransaction.class);
    }

    @Override
    public ResponseEntity<WalletTransaction> agentMeCashOutConfirm(UUID transactionId, String idempotencyKey,
                                                                   AgentMeCashOutConfirmRequest body) {
        return http.ok(money.confirmCashOut(transactionId, http.body(body), idempotencyKey), WalletTransaction.class);
    }

    @Override
    public ResponseEntity<WalletTransaction> agentMeCashOutCancel(UUID transactionId, String idempotencyKey,
                                                                  AgentMeCashOutCancelRequest body) {
        return http.ok(money.cancelCashOut(transactionId, http.body(body), idempotencyKey), WalletTransaction.class);
    }
}
