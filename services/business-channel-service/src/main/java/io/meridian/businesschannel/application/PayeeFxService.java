package io.meridian.businesschannel.application;

import static io.meridian.businesschannel.application.CurrentUser.business;

import io.meridian.businesschannel.application.Access.Need;
import io.meridian.businesschannel.application.Downstream.Query;
import io.meridian.common.error.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Payees (beneficiaries) and FX of the business. Payees and quotes are always created for the business of the token
 * ({@code customer_id}); a quote can only sell the currency of an account the user may initiate on, and is bound to
 * exactly one international payment when used (see {@link PaymentService}).
 */
@Service
public class PayeeFxService {
    static final String PAYMENTS = "payment-service";
    static final String FX = "fx-service";

    private final Downstream down;
    private final Access access;
    private final StepUp stepUp;
    private final ActionAudit audit;
    private final Rows rows;

    public PayeeFxService(Downstream down, Access access, StepUp stepUp, ActionAudit audit, Rows rows) {
        this.down = down;
        this.access = access;
        this.stepUp = stepUp;
        this.audit = audit;
        this.rows = rows;
    }

    // ------------------------------------------------------------------ payees

    public JsonNode payees(Integer limit, String cursor) {
        return down.page(down.get(PAYMENTS, "/v1/payees", Query.of("customer_id", business()).paging(limit, cursor)),
                r -> Access.ofBusiness(r, "customer_id") && !"DELETED".equals(r.path("status").asString("")));
    }

    public JsonNode addPayee(JsonNode req, String key) {
        String action = "businessPayeesCreate";
        access.requireRole(action, "PAYEE", null, "ADMIN", "MAKER");
        String currency = Req.text(req, "currency");
        ObjectNode body = down.json().createObjectNode()
                .put("customer_id", business().toString())
                .put("name", Req.required(req, "name"))
                .put("nickname", Req.text(req, "nickname"))
                .put("beneficiary_type", "BUSINESS")
                .put("account_id_type", Req.required(req, "account_id_type"))
                .put("account_number", Req.required(req, "account_number").replaceAll("\\s+", "").toUpperCase())
                .put("bank_name", Req.text(req, "bank_name"))
                .put("agent_bic", Req.text(req, "agent_bic"))
                .put("agent_clearing_id", Req.text(req, "agent_clearing_id"))
                .put("country", Req.required(req, "country").toUpperCase())
                .put("currency", currency == null ? null : currency.toUpperCase())
                .put("trusted", false);
        stepUp.require(action, "PAYEE", null);
        JsonNode created = down.post(PAYMENTS, "/v1/payees", body, key, "create");
        audit.succeeded(action, "PAYEE", null, created.path("id").asString(null));
        return created;
    }

    public void removePayee(UUID id) {
        String action = "businessPayeesDelete";
        access.requireRole(action, "PAYEE", id, "ADMIN", "MAKER");
        access.payee(id, action);
        down.delete(PAYMENTS, "/v1/payees/" + id);
        audit.succeeded(action, "PAYEE", id, null);
    }

    // ------------------------------------------------------------------ FX

    public JsonNode quotes(Integer limit, String cursor) {
        return down.page(down.get(FX, "/v1/fx/quotes", Query.of("customer_id", business()).paging(limit, cursor)),
                r -> Access.ofBusiness(r, "customer_id"));
    }

    public JsonNode quote(UUID id) {
        return access.fxQuote(id, "businessFxQuotesGet");
    }

    public JsonNode createQuote(JsonNode req, String key) {
        String action = "businessFxQuotesCreate";
        access.requireRole(action, "FX_QUOTE", null, "ADMIN", "MAKER");
        String sell = Req.required(req, "sell_currency").toUpperCase();
        String buy = Req.required(req, "buy_currency").toUpperCase();
        if (sell.equals(buy)) {
            throw ApiException.unprocessable("same_currency", "sell_currency and buy_currency must differ");
        }
        BigDecimal sellAmount = Req.decimal(req, "sell_amount");
        BigDecimal buyAmount = Req.decimal(req, "buy_amount");
        if ((sellAmount == null) == (buyAmount == null)) {
            throw ApiException.unprocessable("fix_one_side", "Give exactly one of sell_amount or buy_amount");
        }
        boolean canSell = access.viewableAccounts().stream().anyMatch(a -> sell.equals(a.path("currency").asString())
                && access.entitled(UUID.fromString(a.path("id").asString()), Need.INITIATE));
        if (!canSell) {
            throw access.notEntitled(action, "FX_QUOTE", null,
                    "You may not initiate payments from any " + sell + " account of the business");
        }
        ObjectNode body = down.json().createObjectNode()
                .put("customer_id", business().toString())
                .put("segment", "BUSINESS")
                .put("channel", "WEB")
                .put("sell_currency", sell)
                .put("buy_currency", buy);
        if (sellAmount != null) {
            body.put("sell_amount", Req.amount(req, "sell_amount").toPlainString());
        } else {
            body.put("buy_amount", Req.amount(req, "buy_amount").toPlainString());
        }
        JsonNode created = down.post(FX, "/v1/fx/quotes", body, key, "quote");
        audit.succeeded(action, "FX_QUOTE", null, created.path("id").asString(null));
        return created;
    }

    /** FX deals booked for the business's released international payments that the user may see. */
    public JsonNode deals() {
        List<JsonNode> out = new ArrayList<>();
        java.util.Set<UUID> viewable = access.viewableAccountIds();
        for (ObjectNode p : rows.list("select row_to_json(t)::text from business_payment t where t.business_customer_id = ? "
                + "and t.kind = 'INTERNATIONAL' and t.payment_id is not null order by t.id desc limit 200", business())) {
            if (!viewable.contains(UUID.fromString(p.path("debtor_account_id").asString()))) {
                continue;
            }
            String pid = p.path("payment_id").asString();
            for (JsonNode d : down.get(FX, "/v1/fx/deals", Query.of("payment_id", pid).with("limit", 10)).path("data")) {
                if (pid.equals(d.path("payment_id").asString(null))) {
                    out.add(d);
                }
            }
        }
        return down.page(out);
    }
}
