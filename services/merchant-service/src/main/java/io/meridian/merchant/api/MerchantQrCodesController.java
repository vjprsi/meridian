package io.meridian.merchant.api;

import io.meridian.merchant.api.generated.MerchantQrCodesApi;
import io.meridian.merchant.api.generated.model.MerchantQr;
import io.meridian.merchant.api.generated.model.MerchantQrCodesDecodeRequest;
import io.meridian.merchant.api.generated.model.MerchantQrCodesPayRequest;
import io.meridian.merchant.api.generated.model.MerchantQrCreate;
import io.meridian.merchant.api.generated.model.WalletTransaction;
import io.meridian.merchant.application.AcceptanceService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** EMVCo merchant-presented QR codes: issue, decode, pay, deactivate. */
@RestController
public class MerchantQrCodesController implements MerchantQrCodesApi {
    private final AcceptanceService acceptance;
    private final Actions actions;

    public MerchantQrCodesController(AcceptanceService acceptance, Actions actions) {
        this.acceptance = acceptance;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<MerchantQr> merchantQrCodesCreate(String idempotencyKey, MerchantQrCreate body) {
        return actions.create(idempotencyKey, body, MerchantQr.class, () -> acceptance.createQr(actions.tree(body)));
    }

    @Override
    public ResponseEntity<MerchantQr> merchantQrCodesDeactivate(UUID id, String idempotencyKey) {
        return actions.action(idempotencyKey, id, "deactivate", null, MerchantQr.class, () -> acceptance.deactivateQr(id));
    }

    @Override
    public ResponseEntity<Map<String, Object>> merchantQrCodesDecode(String idempotencyKey, MerchantQrCodesDecodeRequest body) {
        return actions.objectAction(idempotencyKey, "decode", body,
                () -> acceptance.decode(Http.text(actions.tree(body), "payload")));
    }

    @Override
    public ResponseEntity<WalletTransaction> merchantQrCodesPay(String idempotencyKey, MerchantQrCodesPayRequest body) {
        return actions.action(idempotencyKey, null, "pay", body, WalletTransaction.class,
                () -> acceptance.payQr(actions.tree(body), idempotencyKey));
    }
}
