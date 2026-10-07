package io.meridian.agentchannel.api;

import io.meridian.agentchannel.api.generated.AgentMeAirtimeApi;
import io.meridian.agentchannel.api.generated.model.AgentMeAirtimeCreateRequest;
import io.meridian.agentchannel.api.generated.model.AirtimeTopup;
import io.meridian.agentchannel.api.generated.model.AirtimeTopupPage;
import io.meridian.agentchannel.application.MoneyService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Airtime top-ups from the agent's float wallet. */
@RestController
public class AgentMeAirtimeController implements AgentMeAirtimeApi {
    private final MoneyService money;
    private final Http http;

    public AgentMeAirtimeController(MoneyService money, Http http) {
        this.money = money;
        this.http = http;
    }

    @Override
    public ResponseEntity<AirtimeTopupPage> agentMeAirtimeList(String idempotencyKey, Integer limit, String cursor) {
        return http.ok(money.topups(idempotencyKey, limit, cursor), AirtimeTopupPage.class);
    }

    @Override
    public ResponseEntity<AirtimeTopup> agentMeAirtimeCreate(String idempotencyKey, AgentMeAirtimeCreateRequest body) {
        return http.ok(money.buyAirtime(http.body(body), idempotencyKey), AirtimeTopup.class);
    }

    @Override
    public ResponseEntity<AirtimeTopup> agentMeAirtimeGet(UUID topupId) {
        return http.ok(money.topup(topupId), AirtimeTopup.class);
    }
}
