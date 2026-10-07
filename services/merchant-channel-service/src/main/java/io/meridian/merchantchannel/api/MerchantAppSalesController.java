package io.meridian.merchantchannel.api;

import io.meridian.merchantchannel.api.generated.MerchantAppSalesApi;
import io.meridian.merchantchannel.api.generated.model.MerchantAppSalesCreateRequest;
import io.meridian.merchantchannel.api.generated.model.MerchantSale;
import io.meridian.merchantchannel.api.generated.model.MerchantSalePage;
import io.meridian.merchantchannel.application.SaleService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Sales collected with one dynamic EMVCo QR code per sale. */
@RestController
public class MerchantAppSalesController implements MerchantAppSalesApi {
    private final SaleService sales;
    private final Http http;

    public MerchantAppSalesController(SaleService sales, Http http) {
        this.sales = sales;
        this.http = http;
    }

    @Override
    public ResponseEntity<MerchantSalePage> merchantAppSalesList(UUID storeId, String status, OffsetDateTime from,
                                                                 OffsetDateTime to, Integer limit, String cursor) {
        return http.ok(sales.list(storeId, status, from, to, limit, cursor), MerchantSalePage.class);
    }

    @Override
    public ResponseEntity<MerchantSale> merchantAppSalesCreate(String idempotencyKey, MerchantAppSalesCreateRequest body) {
        return http.once(idempotencyKey, body, MerchantSale.class, () -> sales.create(http.body(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<MerchantSale> merchantAppSalesGet(UUID saleId) {
        return http.ok(sales.get(saleId), MerchantSale.class);
    }

    @Override
    public ResponseEntity<MerchantSale> merchantAppSalesCancel(UUID saleId, String idempotencyKey) {
        return http.once(idempotencyKey, null, MerchantSale.class, () -> sales.cancel(saleId, idempotencyKey));
    }
}
