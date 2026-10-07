package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessFxDealsApi;
import io.meridian.businesschannel.api.generated.model.FxDealPage;
import io.meridian.businesschannel.application.PayeeFxService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** FX deals of the business's released international payments. */
@RestController
public class BusinessFxDealsController implements BusinessFxDealsApi {
    private final PayeeFxService fx;
    private final Http http;

    public BusinessFxDealsController(PayeeFxService fx, Http http) {
        this.fx = fx;
        this.http = http;
    }

    @Override
    public ResponseEntity<FxDealPage> businessFxDealsList(Integer limit, String cursor) {
        return http.ok(fx.deals(), FxDealPage.class);
    }
}
