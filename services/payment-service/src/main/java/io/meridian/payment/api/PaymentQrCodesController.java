package io.meridian.payment.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.api.generated.PaymentQrCodesApi;
import io.meridian.payment.api.generated.model.Payment;
import io.meridian.payment.api.generated.model.PaymentQrCode;
import io.meridian.payment.api.generated.model.PaymentQrCodeCreate;
import io.meridian.payment.api.generated.model.PaymentQrCodesDecodeRequest;
import io.meridian.payment.api.generated.model.PaymentQrCodesDisableRequest;
import io.meridian.payment.api.generated.model.PaymentQrCodesPayRequest;
import io.meridian.payment.application.ReceivablesService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** EMVCo QR codes: generation (TLV + CRC16), decoding and verification, payment, disabling. */
@RestController
public class PaymentQrCodesController implements PaymentQrCodesApi {
    private final Actions actions;
    private final ReceivablesService receivables;

    public PaymentQrCodesController(Actions actions, ReceivablesService receivables) {
        this.actions = actions;
        this.receivables = receivables;
    }

    @Override
    public ResponseEntity<PaymentQrCode> paymentQrCodesCreate(String idempotencyKey, PaymentQrCodeCreate body) {
        return actions.create(idempotencyKey, body, PaymentQrCode.class, () -> receivables.createQr(actions.tree(body)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> paymentQrCodesDecode(String idempotencyKey, PaymentQrCodesDecodeRequest body) {
        TenantContext.require();
        String payload = Actions.text(actions.tree(body), "payload");
        return actions.read(receivables.decode(payload));
    }

    @Override
    public ResponseEntity<Payment> paymentQrCodesPay(UUID id, String idempotencyKey, PaymentQrCodesPayRequest body) {
        JsonNode req = actions.tree(body);
        return actions.action(idempotencyKey, id, "pay", body, Payment.class,
                () -> receivables.payQr(id, Actions.uuid(req, "debtor_account_id"), Actions.decimal(req, "amount"), idempotencyKey));
    }

    @Override
    public ResponseEntity<PaymentQrCode> paymentQrCodesDisable(UUID id, String idempotencyKey, PaymentQrCodesDisableRequest body) {
        return actions.action(idempotencyKey, id, "disable", body, PaymentQrCode.class, () -> receivables.disableQr(id));
    }
}
