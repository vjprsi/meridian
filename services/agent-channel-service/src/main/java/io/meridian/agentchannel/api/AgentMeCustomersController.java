package io.meridian.agentchannel.api;

import io.meridian.agentchannel.api.generated.AgentMeCustomersApi;
import io.meridian.agentchannel.api.generated.model.AgentCustomerLookup;
import io.meridian.agentchannel.application.MoneyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Customer wallet lookup (masked, rate-limited, logged). */
@RestController
public class AgentMeCustomersController implements AgentMeCustomersApi {
    private final MoneyService money;
    private final Http http;

    public AgentMeCustomersController(MoneyService money, Http http) {
        this.money = money;
        this.http = http;
    }

    @Override
    public ResponseEntity<AgentCustomerLookup> agentMeCustomersLookup(String msisdn, String walletNumber) {
        return http.ok(money.lookup(msisdn, walletNumber), AgentCustomerLookup.class);
    }
}
