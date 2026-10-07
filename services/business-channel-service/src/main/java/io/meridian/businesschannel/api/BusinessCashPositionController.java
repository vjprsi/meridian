package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessCashPositionApi;
import io.meridian.businesschannel.application.AccountService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Cash position across the accounts the user may see. */
@RestController
public class BusinessCashPositionController implements BusinessCashPositionApi {
    private final AccountService accounts;
    private final Http http;

    public BusinessCashPositionController(AccountService accounts, Http http) {
        this.accounts = accounts;
        this.http = http;
    }

    @Override
    public ResponseEntity<Map<String, Object>> businessCashPositionGet() {
        return http.object(accounts.cashPosition());
    }
}
