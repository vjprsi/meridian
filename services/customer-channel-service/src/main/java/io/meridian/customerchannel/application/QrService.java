package io.meridian.customerchannel.application;

import static io.meridian.customerchannel.application.AccountService.positive;
import static io.meridian.customerchannel.application.AccountService.uuid;
import static io.meridian.customerchannel.application.Ownership.me;
import static io.meridian.customerchannel.application.ProfileService.required;
import static io.meridian.customerchannel.application.ProfileService.text;

import io.meridian.common.error.ApiException;
import io.meridian.customerchannel.application.Downstream.Query;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * QR flows (decode / pay / generate) and merchant/till payments on the wallet surface. QR decode and pay delegate to
 * merchant-service; the customer's receive QR is a consumer-presented payment QR created in payment-service; a
 * merchant/till payment is a MERCHANT_PAYMENT wallet transaction after the till is resolved in merchant-service. Pay
 * flows build the body server-side from a wallet the customer owns and are step-up gated.
 */
@Service
public class QrService {
    private static final String MERCHANTS = "merchant-service";
    private static final String PAYMENTS = "payment-service";
    private static final String WALLETS = "wallet-service";

    private final Downstream down;
    private final Ownership own;
    private final StepUp stepUp;
    private final ActionAudit audit;
    private final ProfileService profile;

    public QrService(Downstream down, Ownership own, StepUp stepUp, ActionAudit audit, ProfileService profile) {
        this.down = down;
        this.own = own;
        this.stepUp = stepUp;
        this.audit = audit;
        this.profile = profile;
    }

    /** Decode a scanned EMVCo QR into confirmable payment details (no money moves; no step-up). */
    public JsonNode decode(JsonNode req, String key) {
        ObjectNode body = down.json().createObjectNode().put("payload", required(req, "payload"));
        return down.post(MERCHANTS, "/v1/merchant-qr-codes/decode", body, key, "decode");
    }

    /** Pay a decoded merchant QR from one of the customer's wallets (step-up). */
    public JsonNode pay(JsonNode req, String key) {
        String action = "myQrPay";
        UUID walletId = uuid(req, "wallet_id", true);
        JsonNode wallet = own.wallet(walletId, action);
        String payload = required(req, "payload");
        stepUp.require(action, "WALLET", walletId);
        ObjectNode body = down.json().createObjectNode()
                .put("payload", payload)
                .put("payer_wallet_id", walletId.toString())
                .put("currency", wallet.path("currency").asString())
                .put("channel", "QR");
        if (text(req, "amount") != null) {
            body.put("amount", positive(req, "amount").toPlainString());
        }
        JsonNode r = down.post(MERCHANTS, "/v1/merchant-qr-codes/pay", body, key, "pay");
        audit.succeeded(action, "WALLET", walletId, r.path("id").asString(null));
        return r;
    }

    /** Generate the customer's consumer-presented receive QR for one of their wallets. */
    public JsonNode generate(JsonNode req, String key) {
        String action = "myQrGenerate";
        UUID walletId = uuid(req, "wallet_id", true);
        JsonNode wallet = own.wallet(walletId, action);
        JsonNode profileRec = profile.profile();
        String country = text(profileRec, "residence_country");
        if (country == null || !country.toUpperCase().matches("[A-Z]{2}")) {
            throw ApiException.unprocessable("country_required",
                    "A residence country is required on the profile to generate a QR");
        }
        String amount = text(req, "amount");
        ObjectNode body = down.json().createObjectNode()
                .put("qr_type", amount != null ? "DYNAMIC" : "STATIC")
                .put("scheme", "INTERNAL")
                .put("customer_id", me().toString())
                .put("credit_wallet_id", walletId.toString())
                .put("currency", wallet.path("currency").asString())
                .put("merchant_name", payeeName(profileRec, wallet))
                .put("merchant_city", "NA")
                .put("country_code", country.toUpperCase());
        if (amount != null) {
            body.put("amount", positive(req, "amount").toPlainString());
        }
        if (text(req, "note") != null) {
            body.put("reference", clip(text(req, "note"), 35));
        }
        return down.post(PAYMENTS, "/v1/payment-qr-codes", body, key, "generate");
    }

    /** Pay a merchant/till from one of the customer's wallets (step-up). */
    public JsonNode merchantPayment(JsonNode req, String key) {
        String action = "myMerchantPaymentsCreate";
        UUID walletId = uuid(req, "wallet_id", true);
        JsonNode wallet = own.wallet(walletId, action);
        String till = required(req, "merchant_till");
        BigDecimal amount = positive(req, "amount");
        JsonNode merchant = resolveMerchant(till);
        stepUp.require(action, "WALLET", walletId);
        ObjectNode body = down.json().createObjectNode()
                .put("txn_type", "MERCHANT_PAYMENT")
                .put("channel", "APP")
                .put("initiator_type", "CUSTOMER")
                .put("payer_wallet_id", walletId.toString())
                .put("merchant_id", merchant.path("id").asString())
                .put("payee_external_ref", till)
                .put("amount", amount.toPlainString())
                .put("currency", wallet.path("currency").asString());
        String settlement = merchant.path("settlement_wallet_id").asString(null);
        if (settlement != null) {
            body.put("payee_wallet_id", settlement);
        }
        if (text(req, "reference") != null) {
            body.put("description", clip(text(req, "reference"), 255));
        }
        JsonNode r = down.post(WALLETS, "/v1/wallet-transactions", body, key, "merchant-payment");
        audit.succeeded(action, "WALLET", walletId, r.path("id").asString(null));
        return r;
    }

    private JsonNode resolveMerchant(String till) {
        for (JsonNode m : down.all(MERCHANTS, "/v1/merchants",
                Query.of("merchant_code", till).with("status", "ACTIVE"), 50)) {
            if (till.equals(m.path("merchant_code").asString(null))
                    && "ACTIVE".equals(m.path("status").asString(""))) {
                return m;
            }
        }
        throw ApiException.unprocessable("merchant_not_found", "No active merchant for that till");
    }

    private static String payeeName(JsonNode profileRec, JsonNode wallet) {
        String name = profileRec.path("display_name").asString(null);
        if (name == null || name.isBlank()) {
            name = wallet.path("display_name").asString(wallet.path("wallet_number").asString("WALLET"));
        }
        return clip(name, 25);
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
