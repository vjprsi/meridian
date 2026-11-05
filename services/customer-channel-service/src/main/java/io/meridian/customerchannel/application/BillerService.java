package io.meridian.customerchannel.application;

import static io.meridian.customerchannel.application.AccountService.positive;
import static io.meridian.customerchannel.application.AccountService.uuid;
import static io.meridian.customerchannel.application.ProfileService.required;
import static io.meridian.customerchannel.application.ProfileService.text;

import io.meridian.common.error.ApiException;
import io.meridian.customerchannel.application.Downstream.Query;
import io.meridian.web.client.ServiceClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Billers, airtime/data top-ups and bill payments, delegated to biller-service. Payment bodies are always built
 * server-side: the payer wallet must be one the customer owns, and top-ups / bill payments are step-up gated. Both
 * are synchronous (the operator / biller confirms before the call returns). Top-ups and bill payments carry no
 * customer id downstream; the customer owns them through their {@code payer_wallet_id}.
 */
@Service
public class BillerService {
    private static final String BILLERS = "biller-service";

    private final Downstream down;
    private final Ownership own;
    private final StepUp stepUp;
    private final ActionAudit audit;

    public BillerService(Downstream down, Ownership own, StepUp stepUp, ActionAudit audit) {
        this.down = down;
        this.own = own;
        this.stepUp = stepUp;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ billers

    public JsonNode billers(String category, String country, String q, Integer limit, String cursor) {
        Query query = Query.none().with("status", "ACTIVE").with("category", category)
                .with("country", country == null ? null : country.toUpperCase()).paging(limit, cursor);
        return down.page(down.get(BILLERS, "/v1/billers", query),
                r -> "ACTIVE".equals(r.path("status").asString("")) && matchesSearch(r, q));
    }

    private static boolean matchesSearch(JsonNode r, String q) {
        if (q == null || q.isBlank()) {
            return true;
        }
        String s = q.toLowerCase();
        return r.path("name").asString("").toLowerCase().contains(s)
                || r.path("biller_code").asString("").toLowerCase().contains(s);
    }

    /** One biller with its products inlined under {@code products}. */
    public JsonNode biller(UUID id) {
        JsonNode biller = getBiller(id);
        ObjectNode out = down.json().createObjectNode();
        if (biller instanceof ObjectNode b) {
            out.setAll(b);
        }
        ArrayNode products = out.putArray("products");
        down.all(BILLERS, "/v1/billers/" + id + "/products", Query.none(), 500).forEach(products::add);
        return out;
    }

    /** Validate a customer reference against the biller's format and fetch the bill (a bill inquiry). */
    public JsonNode validateReference(UUID billerId, JsonNode req, String key) {
        getBiller(billerId);
        ObjectNode body = down.json().createObjectNode().put("account_reference", required(req, "reference"));
        return down.post(BILLERS, "/v1/billers/" + billerId + "/validate-reference", body, key, "validate-reference");
    }

    // ------------------------------------------------------------------ airtime top-ups

    public JsonNode topups(Integer limit, String cursor) {
        return down.page(byOwnedWallets("/v1/airtime-topups"));
    }

    public JsonNode topup(UUID id) {
        return ownedTopup(id, "myAirtimeTopupsGet");
    }

    /** Top up airtime / a data bundle from one of the customer's wallets (synchronous; step-up). */
    public JsonNode createTopup(JsonNode req, String key) {
        String action = "myAirtimeTopupsCreate";
        UUID walletId = uuid(req, "wallet_id", true);
        JsonNode wallet = own.wallet(walletId, action);
        UUID billerId = uuid(req, "biller_id", true);
        getBiller(billerId);
        String msisdn = required(req, "msisdn");
        UUID productId = uuid(req, "biller_product_id", false);
        stepUp.require(action, "WALLET", walletId);
        ObjectNode body = down.json().createObjectNode()
                .put("biller_id", billerId.toString())
                .put("payer_wallet_id", walletId.toString())
                .put("channel", "APP")
                .put("target_msisdn", msisdn)
                .put("currency", wallet.path("currency").asString());
        if (productId != null) {
            body.put("biller_product_id", productId.toString());
        }
        if (text(req, "amount") != null) {
            body.put("amount", positive(req, "amount").toPlainString());
        }
        JsonNode r = down.post(BILLERS, "/v1/airtime-topups", body, key, "create");
        audit.succeeded(action, "AIRTIME_TOPUP", null, r.path("id").asString(null));
        return r;
    }

    /** Receipt / recharge token: the top-up record itself (biller-service has no separate top-up receipt). */
    public JsonNode topupReceipt(UUID id) {
        return ownedTopup(id, "myAirtimeTopupsReceipt");
    }

    // ------------------------------------------------------------------ bill payments

    public JsonNode inquire(JsonNode req, String key) {
        UUID billerId = uuid(req, "biller_id", true);
        getBiller(billerId);
        ObjectNode body = down.json().createObjectNode()
                .put("biller_id", billerId.toString())
                .put("account_reference", required(req, "reference"));
        return down.post(BILLERS, "/v1/bill-inquiries", body, key, "inquire");
    }

    public JsonNode billPayments(Integer limit, String cursor) {
        return down.page(byOwnedWallets("/v1/bill-payments"));
    }

    public JsonNode billPayment(UUID id) {
        return ownedBillPayment(id, "myBillPaymentsGet");
    }

    /** Pay a bill from one of the customer's wallets (synchronous; step-up). */
    public JsonNode createBillPayment(JsonNode req, String key) {
        String action = "myBillPaymentsCreate";
        UUID walletId = uuid(req, "wallet_id", true);
        JsonNode wallet = own.wallet(walletId, action);
        UUID billerId = uuid(req, "biller_id", true);
        getBiller(billerId);
        String reference = required(req, "reference");
        String amount = positive(req, "amount").toPlainString();
        UUID inquiryId = uuid(req, "bill_inquiry_id", false);
        stepUp.require(action, "WALLET", walletId);
        ObjectNode body = down.json().createObjectNode()
                .put("biller_id", billerId.toString())
                .put("payer_wallet_id", walletId.toString())
                .put("channel", "APP")
                .put("account_reference", reference)
                .put("amount", amount)
                .put("currency", wallet.path("currency").asString());
        if (inquiryId != null) {
            body.put("bill_inquiry_id", inquiryId.toString());
        }
        JsonNode r = down.post(BILLERS, "/v1/bill-payments", body, key, "create");
        audit.succeeded(action, "BILL_PAYMENT", null, r.path("id").asString(null));
        return r;
    }

    /** Receipt / confirmation token; biller-service returns it as plain text, wrapped here as {@code {receipt}}. */
    public JsonNode billPaymentReceipt(UUID id) {
        ownedBillPayment(id, "myBillPaymentsReceipt");
        ServiceClient.Download d = down.bytes(BILLERS, "/v1/bill-payments/" + id + "/receipt");
        return down.json().createObjectNode().put("receipt", new String(d.content(), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ helpers

    /** All rows of a wallet-keyed list (top-ups / bill payments) across the customer's own wallets. */
    private List<JsonNode> byOwnedWallets(String path) {
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode w : own.customerWallets()) {
            String id = w.path("id").asString();
            for (JsonNode r : down.all(BILLERS, path, Query.of("payer_wallet_id", id), 500)) {
                if (id.equals(r.path("payer_wallet_id").asString(null))) {
                    rows.add(r);
                }
            }
        }
        return rows;
    }

    private JsonNode getBiller(UUID id) {
        try {
            return down.get(BILLERS, "/v1/billers/" + id);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw Ownership.notFound("BILLER", id);
            }
            throw e;
        }
    }

    private JsonNode ownedTopup(UUID id, String action) {
        JsonNode t = fetch("/v1/airtime-topups/" + id, "AIRTIME_TOPUP", id);
        assertOwnsWallet(t.path("payer_wallet_id").asString(null), action, "AIRTIME_TOPUP", id);
        return t;
    }

    private JsonNode ownedBillPayment(UUID id, String action) {
        JsonNode p = fetch("/v1/bill-payments/" + id, "BILL_PAYMENT", id);
        assertOwnsWallet(p.path("payer_wallet_id").asString(null), action, "BILL_PAYMENT", id);
        return p;
    }

    private JsonNode fetch(String path, String type, UUID id) {
        try {
            return down.get(BILLERS, path);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw Ownership.notFound(type, id);
            }
            throw e;
        }
    }

    /** The payer wallet must be one the customer owns, else the record answers 404 like a missing one. */
    private void assertOwnsWallet(String walletId, String action, String type, UUID id) {
        if (walletId == null) {
            throw Ownership.notFound(type, id);
        }
        try {
            own.wallet(UUID.fromString(walletId), action);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw Ownership.notFound(type, id);
            }
            throw e;
        }
    }
}
