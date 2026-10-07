package io.meridian.merchantchannel.api;

import io.meridian.merchantchannel.api.generated.MerchantAppTerminalsApi;
import io.meridian.merchantchannel.api.generated.model.MerchantAppTerminalsDeactivateRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantTerminal;
import io.meridian.merchantchannel.api.generated.model.MerchantTerminalPage;
import io.meridian.merchantchannel.application.StoreService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Terminals (tills) of the stores the user may work in. */
@RestController
public class MerchantAppTerminalsController implements MerchantAppTerminalsApi {
    private final StoreService stores;
    private final Http http;

    public MerchantAppTerminalsController(StoreService stores, Http http) {
        this.stores = stores;
        this.http = http;
    }

    @Override
    public ResponseEntity<MerchantTerminalPage> merchantAppTerminalsList(UUID storeId, Integer limit, String cursor) {
        return http.ok(stores.terminals(storeId), MerchantTerminalPage.class);
    }

    @Override
    public ResponseEntity<MerchantTerminal> merchantAppTerminalsGet(UUID terminalId) {
        return http.ok(stores.terminal(terminalId), MerchantTerminal.class);
    }

    @Override
    public ResponseEntity<MerchantTerminal> merchantAppTerminalsDeactivate(UUID terminalId, String idempotencyKey,
            MerchantAppTerminalsDeactivateRequest body) {
        return http.ok(stores.deactivateTerminal(terminalId, http.body(body), idempotencyKey), MerchantTerminal.class);
    }
}
