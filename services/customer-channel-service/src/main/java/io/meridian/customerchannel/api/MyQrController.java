package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyQrApi;
import io.meridian.customerchannel.api.generated.model.MyQrDecodeRequest;
import io.meridian.customerchannel.api.generated.model.MyQrGenerateRequest;
import io.meridian.customerchannel.api.generated.model.MyQrPayRequest;
import io.meridian.customerchannel.api.generated.model.WalletTransaction;
import io.meridian.customerchannel.application.QrService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** QR flows: decode a scanned QR, pay it (step-up), and generate the customer's receive QR. */
@RestController
public class MyQrController implements MyQrApi {
    private final QrService qr;
    private final Http http;

    public MyQrController(QrService qr, Http http) {
        this.qr = qr;
        this.http = http;
    }

    @Override
    public ResponseEntity<Map<String, Object>> myQrDecode(String idempotencyKey, MyQrDecodeRequest body) {
        return http.object(qr.decode(http.body(body), idempotencyKey));
    }

    @Override
    public ResponseEntity<WalletTransaction> myQrPay(String idempotencyKey, MyQrPayRequest body) {
        return http.ok(qr.pay(http.body(body), idempotencyKey), WalletTransaction.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> myQrGenerate(String idempotencyKey, MyQrGenerateRequest body) {
        return http.object(qr.generate(http.body(body), idempotencyKey));
    }
}
