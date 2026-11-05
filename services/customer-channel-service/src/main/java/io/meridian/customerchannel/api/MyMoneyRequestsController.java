package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyMoneyRequestsApi;
import io.meridian.customerchannel.api.generated.model.MyMoneyRequestsCreateRequest;
import io.meridian.customerchannel.api.generated.model.MyMoneyRequestsDeclineRequest;
import io.meridian.customerchannel.api.generated.model.MyMoneyRequestsPayRequest;
import io.meridian.customerchannel.api.generated.model.WalletTransaction;
import io.meridian.customerchannel.application.MoneyRequestService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer's money requests (request-to-pay): list, create, pay (step-up), decline and cancel. */
@RestController
public class MyMoneyRequestsController implements MyMoneyRequestsApi {
    private final MoneyRequestService requests;
    private final Http http;

    public MyMoneyRequestsController(MoneyRequestService requests, Http http) {
        this.requests = requests;
        this.http = http;
    }

    @Override
    public ResponseEntity<Map<String, Object>> myMoneyRequestsGet(String direction) {
        return http.object(requests.list(direction));
    }

    @Override
    public ResponseEntity<Map<String, Object>> myMoneyRequestsCreate(String idempotencyKey,
                                                                     MyMoneyRequestsCreateRequest body) {
        return http.object(requests.create(http.body(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<WalletTransaction> myMoneyRequestsPay(UUID requestId, String idempotencyKey,
                                                                MyMoneyRequestsPayRequest body) {
        return http.ok(requests.pay(requestId, http.body(body), idempotencyKey), WalletTransaction.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> myMoneyRequestsDecline(UUID requestId, String idempotencyKey,
                                                                      MyMoneyRequestsDeclineRequest body) {
        return http.object(requests.decline(requestId, http.body(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<Map<String, Object>> myMoneyRequestsCancel(UUID requestId, String idempotencyKey) {
        return http.object(requests.cancel(requestId, idempotencyKey));
    }
}
