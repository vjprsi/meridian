package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyStandingOrdersApi;
import io.meridian.customerchannel.api.generated.model.MyStandingOrdersCreateRequest;
import io.meridian.customerchannel.api.generated.model.StandingOrder;
import io.meridian.customerchannel.api.generated.model.StandingOrderPage;
import io.meridian.customerchannel.application.AccountService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Standing orders on the customer's accounts. */
@RestController
public class MyStandingOrdersController implements MyStandingOrdersApi {
    private final AccountService accounts;
    private final Http http;

    public MyStandingOrdersController(AccountService accounts, Http http) {
        this.accounts = accounts;
        this.http = http;
    }

    @Override
    public ResponseEntity<StandingOrderPage> myStandingOrdersList(UUID accountId, Integer limit, String cursor) {
        return http.ok(accounts.standingOrders(accountId, limit, cursor), StandingOrderPage.class);
    }

    @Override
    public ResponseEntity<StandingOrder> myStandingOrdersCreate(String idempotencyKey,
                                                                MyStandingOrdersCreateRequest body) {
        return http.ok(accounts.createStandingOrder(http.body(body), idempotencyKey), StandingOrder.class);
    }

    @Override
    public ResponseEntity<StandingOrder> myStandingOrdersPause(UUID standingOrderId, String idempotencyKey) {
        return http.ok(accounts.standingOrderAction(standingOrderId, "pause", idempotencyKey), StandingOrder.class);
    }

    @Override
    public ResponseEntity<StandingOrder> myStandingOrdersResume(UUID standingOrderId, String idempotencyKey) {
        return http.ok(accounts.standingOrderAction(standingOrderId, "resume", idempotencyKey), StandingOrder.class);
    }

    @Override
    public ResponseEntity<StandingOrder> myStandingOrdersCancel(UUID standingOrderId, String idempotencyKey) {
        return http.ok(accounts.standingOrderAction(standingOrderId, "cancel", idempotencyKey), StandingOrder.class);
    }
}
