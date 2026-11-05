package io.meridian.customerchannel.application;

import static io.meridian.customerchannel.application.AccountService.positive;
import static io.meridian.customerchannel.application.AccountService.uuid;
import static io.meridian.customerchannel.application.Ownership.me;
import static io.meridian.customerchannel.application.Ownership.mine;
import static io.meridian.customerchannel.application.ProfileService.required;
import static io.meridian.customerchannel.application.ProfileService.text;

import io.meridian.common.error.ApiException;
import io.meridian.customerchannel.application.Downstream.Query;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Money requests (request-to-pay) on the wallet surface, delegated to payment-service requests-to-pay. The customer
 * is the requester for outgoing requests and the payer for incoming ones. Paying a request builds the body
 * server-side from a wallet the customer owns and is step-up gated.
 *
 * <p>Note: payment-service requests-to-pay is account-centric (accept takes a {@code debtor_account_id} and returns a
 * Payment); the wallet surface passes the owned wallet id as the debtor and the response is mapped onto the wallet
 * transaction shape. A wallet-native collect endpoint, if added later, should replace the accept delegation.
 */
@Service
public class MoneyRequestService {
    private static final String PAYMENTS = "payment-service";

    private final Downstream down;
    private final Ownership own;
    private final StepUp stepUp;
    private final ActionAudit audit;

    public MoneyRequestService(Downstream down, Ownership own, StepUp stepUp, ActionAudit audit) {
        this.down = down;
        this.own = own;
        this.stepUp = stepUp;
        this.audit = audit;
    }

    public JsonNode list(String direction) {
        boolean outgoing = direction == null || "outgoing".equalsIgnoreCase(direction);
        boolean incoming = direction == null || "incoming".equalsIgnoreCase(direction);
        if (!outgoing && !incoming) {                 // unknown value: show both rather than nothing
            outgoing = true;
            incoming = true;
        }
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        if (outgoing) {
            for (JsonNode r : down.all(PAYMENTS, "/v1/requests-to-pay",
                    Query.of("requester_customer_id", me()), 500)) {
                if (mine(r, "requester_customer_id")) {
                    rows.put(r.path("id").asString(), r);
                }
            }
        }
        if (incoming) {
            for (JsonNode r : down.all(PAYMENTS, "/v1/requests-to-pay",
                    Query.of("payer_customer_id", me()), 500)) {
                if (mine(r, "payer_customer_id")) {
                    rows.put(r.path("id").asString(), r);
                }
            }
        }
        return down.page(new ArrayList<>(rows.values()));
    }

    /** Request money from another wallet by phone / wallet number; the payer is notified and may approve. */
    public JsonNode create(JsonNode req, String key) {
        String action = "myMoneyRequestsCreate";
        String type = required(req, "type");
        String value = required(req, "value");
        String amount = positive(req, "amount").toPlainString();
        JsonNode wallet = defaultWallet();
        ObjectNode body = down.json().createObjectNode()
                .put("requester_customer_id", me().toString())
                .put("requester_wallet_id", wallet.path("id").asString())
                .put("amount", amount)
                .put("currency", wallet.path("currency").asString())
                .put("expires_at", OffsetDateTime.now(ZoneOffset.UTC).plusDays(7).toString())
                .put("description", text(req, "note"));
        switch (type) {
            case "MSISDN" -> body.put("payer_alias_type", "PHONE").put("payer_alias_value", value);
            case "WALLET_NUMBER" -> body.put("payer_alias_type", "USERNAME").put("payer_alias_value", value);
            default -> throw ApiException.unprocessable("invalid_type", "type must be MSISDN or WALLET_NUMBER");
        }
        JsonNode r = down.post(PAYMENTS, "/v1/requests-to-pay", body, key, "create");
        audit.succeeded(action, "MONEY_REQUEST", null, r.path("id").asString(null));
        return r;
    }

    /** Approve and pay an incoming request from one of the customer's wallets (step-up). */
    public JsonNode pay(UUID id, JsonNode req, String key) {
        String action = "myMoneyRequestsPay";
        ownedRequest(id, "payer_customer_id", action);
        UUID walletId = uuid(req, "wallet_id", true);
        own.wallet(walletId, action);
        stepUp.require(action, "MONEY_REQUEST", id);
        ObjectNode body = down.json().createObjectNode().put("debtor_account_id", walletId.toString());
        JsonNode r = down.post(PAYMENTS, "/v1/requests-to-pay/" + id + "/accept", body, key, "accept");
        audit.succeeded(action, "MONEY_REQUEST", id, r.path("id").asString(null));
        return r;
    }

    public JsonNode decline(UUID id, JsonNode req, String key) {
        String action = "myMoneyRequestsDecline";
        ownedRequest(id, "payer_customer_id", action);
        ObjectNode body = down.json().createObjectNode();
        if (text(req, "reason") != null) {
            body.put("reason", text(req, "reason"));
        }
        JsonNode r = down.post(PAYMENTS, "/v1/requests-to-pay/" + id + "/decline", body, key, "decline");
        audit.succeeded(action, "MONEY_REQUEST", id, null);
        return r;
    }

    public JsonNode cancel(UUID id, String key) {
        String action = "myMoneyRequestsCancel";
        ownedRequest(id, "requester_customer_id", action);
        JsonNode r = down.post(PAYMENTS, "/v1/requests-to-pay/" + id + "/cancel",
                down.json().createObjectNode(), key, "cancel");
        audit.succeeded(action, "MONEY_REQUEST", id, null);
        return r;
    }

    /** The request-to-pay must reference the customer on the given side ({@code requester}/{@code payer}); else 404. */
    private JsonNode ownedRequest(UUID id, String field, String action) {
        JsonNode r;
        try {
            r = down.get(PAYMENTS, "/v1/requests-to-pay/" + id);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw Ownership.notFound("MONEY_REQUEST", id);
            }
            throw e;
        }
        if (!mine(r, field)) {
            throw own.deny(action, "MONEY_REQUEST", id);
        }
        return r;
    }

    /** The customer's default (or first) own wallet, for the requested credit side and currency. */
    private JsonNode defaultWallet() {
        List<JsonNode> wallets = own.customerWallets();
        if (wallets.isEmpty()) {
            throw ApiException.unprocessable("no_wallet", "You have no wallet to receive the requested money");
        }
        return wallets.stream().filter(w -> w.path("is_default").asBoolean(false)).findFirst().orElse(wallets.get(0));
    }
}
