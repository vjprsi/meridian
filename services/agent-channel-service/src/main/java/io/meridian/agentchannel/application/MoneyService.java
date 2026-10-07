package io.meridian.agentchannel.application;

import static io.meridian.agentchannel.application.Ownership.me;
import static io.meridian.agentchannel.application.Ownership.mine;

import io.meridian.agentchannel.application.Downstream.Query;
import io.meridian.agentchannel.config.AgentChannelProperties;
import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.crud.CrudEngine;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Money movement at the agent: customer wallet lookup (masked), quotes, cash-in, cash-out, bill payments, airtime,
 * the agent's transactions and receipts. Payer / payee wallets, outlet, currency and channel (AGENT_APP) are set
 * server-side; money movement needs a recent login or a device proof ({@link StepUp}).
 */
@Service
public class MoneyService {
    private static final String WALLETS = "wallet-service";
    private static final String BILLERS = "biller-service";
    private static final String CHANNEL = "AGENT_APP";
    private static final Set<String> QUOTE_TYPES = Set.of("CASH_IN", "CASH_OUT", "BILL_PAYMENT", "AIRTIME");
    private static final int MAX_RECONCILE_ROWS = 2000;

    private final Downstream down;
    private final Ownership own;
    private final StepUp stepUp;
    private final ActionAudit audit;
    private final AgentChannelProperties props;
    private final CrudEngine crud;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public MoneyService(Downstream down, Ownership own, StepUp stepUp, ActionAudit audit, AgentChannelProperties props,
                        CrudEngine crud, JdbcClient jdbc, TransactionTemplate tx, JsonMapper json) {
        this.down = down;
        this.own = own;
        this.stepUp = stepUp;
        this.audit = audit;
        this.props = props;
        this.crud = crud;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    // ------------------------------------------------------------------ customer lookup

    /**
     * An ACTIVE customer wallet by mobile number or wallet number, in the agent's currency. Only masked data leaves the
     * channel (name initials, masked number, status, tier, wallet id for cash-out); every lookup is logged and the
     * number of lookups per agent and hour is capped (429 rate_limited) against enumeration.
     */
    public JsonNode lookup(String msisdn, String walletNumber) {
        String action = "agentMeCustomersLookup";
        boolean byPhone = msisdn != null && !msisdn.isBlank();
        boolean byNumber = walletNumber != null && !walletNumber.isBlank();
        if (byPhone == byNumber) {
            throw ApiException.badRequest("lookup_key_required", "Give exactly one of msisdn or wallet_number");
        }
        own.activeAgent();
        long recent = jdbc.sql("select count(*) from agent_customer_lookup where agent_id = ? "
                        + "and created_at > now() - interval '1 hour'")
                .param(me()).query(Long.class).single();
        if (recent >= props.lookupsPerHour()) {
            audit.rateLimited(action, "WALLET");
            throw new ApiException(429, "rate_limited", "Too many customer lookups; try again later");
        }
        JsonNode wallet = null;
        try {
            wallet = down.get(WALLETS, "/v1/wallets/lookup", Query.of(byPhone ? "msisdn" : "wallet_number",
                    byPhone ? msisdn.trim() : walletNumber.trim()).with("currency", own.currency()));
        } catch (ApiException e) {
            if (e.status() != 404) {
                throw e;
            }
        }
        boolean found = wallet != null && "CUSTOMER".equals(wallet.path("wallet_type").asString(null))
                && "ACTIVE".equals(wallet.path("status").asString(null));
        ObjectNode row = json.createObjectNode()
                .put("agent_id", me().toString())
                .put("lookup_type", byPhone ? "MSISDN" : "WALLET_NUMBER")
                .put("found", found);
        if (found) {
            row.put("wallet_id", wallet.path("id").asString())
                    .put("display_name_masked", maskName(wallet.path("display_name").asString("")))
                    .put("msisdn_masked", maskMsisdn(wallet.path("msisdn").asString(null)))
                    .put("currency", wallet.path("currency").asString(null))
                    .put("wallet_status", wallet.path("status").asString(null))
                    .put("kyc_tier", wallet.path("kyc_tier").asInt(0));
        }
        JsonNode saved = tx.execute(s -> json.readTree(crud.insert("agent_customer_lookup", row)));
        if (!found) {
            throw new ApiException(404, "wallet_not_found", "No active customer wallet matches");
        }
        return saved;
    }

    static String maskName(String name) {
        StringBuilder out = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(part.charAt(0)).append("***");
        }
        return out.toString();
    }

    static String maskMsisdn(String msisdn) {
        if (msisdn == null || msisdn.length() < 8) {
            return msisdn == null ? null : "***";
        }
        return msisdn.substring(0, 4) + "*".repeat(msisdn.length() - 7) + msisdn.substring(msisdn.length() - 3);
    }

    // ------------------------------------------------------------------ quotes

    public JsonNode quote(JsonNode req, String key) {
        String type = Req.required(req, "txn_type");
        if (!QUOTE_TYPES.contains(type)) {
            throw ApiException.unprocessable("invalid_txn_type", "txn_type must be one of " + QUOTE_TYPES);
        }
        own.agent();
        String floatWallet = own.floatWallet().toString();
        ObjectNode body = down.object()
                .put("txn_type", type)
                .put("amount", Req.amount(req, "amount"))
                .put("currency", own.currency())
                .put("channel", CHANNEL);
        switch (type) {
            case "CASH_IN" -> body.put("payer_wallet_id", floatWallet)
                    .put("payee_msisdn", Req.required(req, "payee_msisdn"));
            case "CASH_OUT" -> body.put("payer_wallet_id", customerWallet(Req.uuid(req, "payer_wallet_id", true),
                    "agentMeTransactionsQuote").path("id").asString()).put("payee_wallet_id", floatWallet);
            default -> body.put("payer_wallet_id", floatWallet);
        }
        return down.post(WALLETS, "/v1/wallet-transactions/quote", body, key, "quote");
    }

    // ------------------------------------------------------------------ cash-in / cash-out

    public JsonNode cashIn(JsonNode req, String key) {
        String action = "agentMeCashInCreate";
        own.activeAgent();
        UUID outlet = outlet(req, action);
        ObjectNode body = down.object()
                .put("agent_wallet_id", own.floatWallet().toString())
                .put("payee_msisdn", Req.required(req, "payee_msisdn"))
                .put("amount", Req.amount(req, "amount"))
                .put("currency", own.currency())
                .put("channel", CHANNEL);
        if (outlet != null) {
            body.put("agent_outlet_id", outlet.toString());
        }
        pin(body, req, "pin");
        stepUp.require(action, "TRANSACTION", null);
        JsonNode r = down.post(WALLETS, "/v1/wallet-transactions/cash-in", body, key, "cash-in");
        audit.succeeded(action, "TRANSACTION", null, r.path("id").asString(null));
        return r;
    }

    /** Starts a cash-out from an ACTIVE customer wallet; the customer confirms with PIN or OTP. */
    public JsonNode cashOut(JsonNode req, String key) {
        String action = "agentMeCashOutCreate";
        own.activeAgent();
        UUID outlet = outlet(req, action);
        JsonNode payer = customerWallet(Req.uuid(req, "payer_wallet_id", true), action);
        ObjectNode body = down.object()
                .put("agent_wallet_id", own.floatWallet().toString())
                .put("payer_wallet_id", payer.path("id").asString())
                .put("amount", Req.amount(req, "amount"))
                .put("currency", own.currency())
                .put("channel", CHANNEL);
        if (outlet != null) {
            body.put("agent_outlet_id", outlet.toString());
        }
        stepUp.require(action, "TRANSACTION", null);
        JsonNode r = down.post(WALLETS, "/v1/wallet-transactions/cash-out", body, key, "cash-out");
        audit.succeeded(action, "TRANSACTION", null, r.path("id").asString(null));
        return r;
    }

    public JsonNode confirmCashOut(UUID id, JsonNode req, String key) {
        String action = "agentMeCashOutConfirm";
        myCashOut(id, action);
        ObjectNode body = down.object();
        pin(body, req, "pin");
        pin(body, req, "otp");
        if (!body.has("pin") && !body.has("otp")) {
            throw ApiException.unprocessable("confirmation_required", "The customer's PIN or OTP is required");
        }
        stepUp.require(action, "TRANSACTION", id);
        JsonNode r = down.post(WALLETS, "/v1/wallet-transactions/" + id + "/confirm", body, key, "cash-out-confirm:" + id);
        audit.succeeded(action, "TRANSACTION", id, null);
        return r;
    }

    public JsonNode cancelCashOut(UUID id, JsonNode req, String key) {
        String action = "agentMeCashOutCancel";
        myCashOut(id, action);
        ObjectNode body = down.object().put("reason", Req.text(req, "reason"));
        JsonNode r = down.post(WALLETS, "/v1/wallet-transactions/" + id + "/cancel", body, key, "cash-out-cancel:" + id);
        audit.succeeded(action, "TRANSACTION", id, null);
        return r;
    }

    private JsonNode myCashOut(UUID id, String action) {
        JsonNode t = own.transaction(id, action);
        if (!"CASH_OUT".equals(t.path("txn_type").asString(null)) || !mine(t, "agent_id")) {
            throw own.deny(action, "TRANSACTION", id);
        }
        return t;
    }

    // ------------------------------------------------------------------ transactions and receipts

    /**
     * Transactions of the agent's float wallet (cash-in, cash-out, float, bills, airtime), newest first. With
     * {@code idempotency_key}: the cash-in / cash-out created with that client key, searched within the reconcile
     * window (wallet-service cannot filter by key).
     */
    public JsonNode transactions(OffsetDateTime from, OffsetDateTime to, String type, String status, String clientKey,
                                 Integer limit, String cursor) {
        String path = "/v1/wallets/" + own.floatWallet() + "/transactions";
        if (clientKey != null && !clientKey.isBlank()) {
            Set<String> keys = Set.of(Downstream.key(clientKey, "cash-in"), Downstream.key(clientKey, "cash-out"));
            return reconcile(WALLETS, path, Query.of("from", since()), r -> keys.contains(r.path("idempotency_key")
                    .asString("")) && own.isMyTransaction(r));
        }
        return down.page(down.get(WALLETS, path, Query.of("from", from).with("to", to).with("txn_type", type)
                .with("status", status).paging(limit, cursor)), own::isMyTransaction);
    }

    public JsonNode transaction(UUID id) {
        return own.transaction(id, "agentMeTransactionsGet");
    }

    public ServiceClient.Download receipt(UUID id) {
        own.transaction(id, "agentMeTransactionsReceipt");
        return down.bytes(WALLETS, "/v1/wallet-transactions/" + id + "/receipt");
    }

    // ------------------------------------------------------------------ billers, bills and airtime

    public JsonNode billers(String category, Integer limit, String cursor) {
        return down.page(down.get(BILLERS, "/v1/billers", Query.of("status", "ACTIVE").with("category", category)
                .paging(limit, cursor)), r -> "ACTIVE".equals(r.path("status").asString(null)));
    }

    public JsonNode billerProducts(UUID billerId, Integer limit, String cursor) {
        activeBiller(billerId);
        return down.page(down.get(BILLERS, "/v1/billers/" + billerId + "/products", Query.none().paging(limit, cursor)),
                r -> r.path("is_active").asBoolean(true));
    }

    public JsonNode validateReference(UUID billerId, JsonNode req, String key) {
        activeBiller(billerId);
        ObjectNode body = down.object().put("account_reference", Req.required(req, "account_reference"));
        return down.post(BILLERS, "/v1/billers/" + billerId + "/validate-reference", body, key, "validate:" + billerId);
    }

    public JsonNode billPayments(String clientKey, Integer limit, String cursor) {
        String wallet = own.floatWallet().toString();
        if (clientKey != null && !clientKey.isBlank()) {
            String k = Downstream.key(clientKey, "bill-payment");
            return reconcile(BILLERS, "/v1/bill-payments", Query.of("payer_wallet_id", wallet).with("created_after", since()),
                    r -> k.equals(r.path("idempotency_key").asString("")) && wallet.equals(r.path("payer_wallet_id")
                            .asString(null)));
        }
        return down.page(down.get(BILLERS, "/v1/bill-payments", Query.of("payer_wallet_id", wallet).paging(limit, cursor)),
                r -> wallet.equals(r.path("payer_wallet_id").asString(null)));
    }

    public JsonNode billPayment(UUID id) {
        return own.billPayment(id, "agentMeBillPaymentsGet");
    }

    public ServiceClient.Download billReceipt(UUID id) {
        own.billPayment(id, "agentMeBillPaymentsReceipt");
        return down.bytes(BILLERS, "/v1/bill-payments/" + id + "/receipt");
    }

    public JsonNode payBill(JsonNode req, String key) {
        String action = "agentMeBillPaymentsCreate";
        own.activeAgent();
        UUID billerId = Req.uuid(req, "biller_id", true);
        activeBiller(billerId);
        ObjectNode body = down.object()
                .put("biller_id", billerId.toString())
                .put("payer_wallet_id", own.floatWallet().toString())
                .put("channel", CHANNEL)
                .put("account_reference", Req.required(req, "account_reference"))
                .put("amount", Req.amount(req, "amount"))
                .put("currency", own.currency());
        optionalUuid(body, req, "biller_product_id");
        optionalUuid(body, req, "bill_inquiry_id");
        String name = Req.text(req, "customer_name");
        if (name != null && !name.isBlank()) {
            body.put("customer_name", name);
        }
        pin(body, req, "pin");
        stepUp.require(action, "BILL_PAYMENT", null);
        JsonNode r = down.post(BILLERS, "/v1/bill-payments", body, key, "bill-payment");
        audit.succeeded(action, "BILL_PAYMENT", null, r.path("id").asString(null));
        return r;
    }

    public JsonNode topups(String clientKey, Integer limit, String cursor) {
        String wallet = own.floatWallet().toString();
        if (clientKey != null && !clientKey.isBlank()) {
            String k = Downstream.key(clientKey, "airtime");
            return reconcile(BILLERS, "/v1/airtime-topups", Query.of("payer_wallet_id", wallet).with("created_after", since()),
                    r -> k.equals(r.path("idempotency_key").asString("")) && wallet.equals(r.path("payer_wallet_id")
                            .asString(null)));
        }
        return down.page(down.get(BILLERS, "/v1/airtime-topups", Query.of("payer_wallet_id", wallet).paging(limit, cursor)),
                r -> wallet.equals(r.path("payer_wallet_id").asString(null)));
    }

    public JsonNode topup(UUID id) {
        return own.topup(id, "agentMeAirtimeGet");
    }

    public JsonNode buyAirtime(JsonNode req, String key) {
        String action = "agentMeAirtimeCreate";
        own.activeAgent();
        UUID billerId = Req.uuid(req, "biller_id", true);
        activeBiller(billerId);
        ObjectNode body = down.object()
                .put("biller_id", billerId.toString())
                .put("payer_wallet_id", own.floatWallet().toString())
                .put("channel", CHANNEL)
                .put("target_msisdn", Req.required(req, "target_msisdn"))
                .put("amount", Req.amount(req, "amount"))
                .put("currency", own.currency());
        optionalUuid(body, req, "biller_product_id");
        pin(body, req, "pin");
        stepUp.require(action, "AIRTIME_TOPUP", null);
        JsonNode r = down.post(BILLERS, "/v1/airtime-topups", body, key, "airtime");
        audit.succeeded(action, "AIRTIME_TOPUP", null, r.path("id").asString(null));
        return r;
    }

    // ------------------------------------------------------------------ helpers

    /** An ACTIVE customer wallet (any other wallet answers 404 like a missing one). */
    private JsonNode customerWallet(UUID id, String action) {
        JsonNode w;
        try {
            w = down.get(WALLETS, "/v1/wallets/" + id);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw Ownership.notFound("WALLET", id);
            }
            throw e;
        }
        if (!"CUSTOMER".equals(w.path("wallet_type").asString(null)) || !"ACTIVE".equals(w.path("status").asString(null))) {
            audit.denied(action, "WALLET", id, "not_a_customer_wallet");
            throw Ownership.notFound("WALLET", id);
        }
        return w;
    }

    private void activeBiller(UUID id) {
        JsonNode b;
        try {
            b = down.get(BILLERS, "/v1/billers/" + id);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw Ownership.notFound("BILLER", id);
            }
            throw e;
        }
        if (!"ACTIVE".equals(b.path("status").asString(null))) {
            throw Ownership.notFound("BILLER", id);
        }
    }

    /** The outlet of the request when given: one of the agent's ACTIVE outlets (404 otherwise). */
    private UUID outlet(JsonNode req, String action) {
        UUID outlet = Req.uuid(req, "agent_outlet_id", false);
        if (outlet != null) {
            own.activeOutlet(outlet, action);
        }
        return outlet;
    }

    private static void pin(ObjectNode body, JsonNode req, String field) {
        String v = Req.text(req, field);
        if (v != null && !v.isBlank()) {
            body.put(field, v);
        }
    }

    private static void optionalUuid(ObjectNode body, JsonNode req, String field) {
        UUID v = Req.uuid(req, field, false);
        if (v != null) {
            body.put(field, v.toString());
        }
    }

    private OffsetDateTime since() {
        return Instant.now().minus(props.reconcileWindow()).atOffset(ZoneOffset.UTC);
    }

    private JsonNode reconcile(String service, String path, Query query, Predicate<JsonNode> match) {
        List<JsonNode> rows = down.all(service, path, query, MAX_RECONCILE_ROWS).stream().filter(match).toList();
        return down.page(rows);
    }
}
