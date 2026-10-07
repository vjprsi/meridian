package io.meridian.customerchannel.application;

import static io.meridian.customerchannel.application.AccountService.positive;
import static io.meridian.customerchannel.application.AccountService.uuid;
import static io.meridian.customerchannel.application.Ownership.me;
import static io.meridian.customerchannel.application.Ownership.mine;
import static io.meridian.customerchannel.application.ProfileService.required;
import static io.meridian.customerchannel.application.ProfileService.text;

import io.meridian.common.error.ApiException;
import io.meridian.customerchannel.application.Downstream.Query;
import io.meridian.customerchannel.application.Ownership.Access;
import io.meridian.customerchannel.config.ChannelProperties;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Payees and payments. Payment bodies are always built here: the customer comes from the token, the debit account
 * must be one the customer may transact on, the credit side is either another account of the customer or one of the
 * customer's active payees (creditor details copied from the payee record), and the channel is WEB or MOBILE.
 */
@Service
public class PaymentService {
    private static final String PAYMENTS = "payment-service";
    private static final Set<String> CHANNELS = Set.of("WEB", "MOBILE");
    /** Fraud-control internals of a payment never shown to the customer (scores, alert, session context). */
    static final Set<String> RISK_INTERNAL = Set.of("fraud_score", "fraud_score_id", "fraud_decision", "fraud_alert_id",
            "fraud_degraded", "risk_context", "screening_result_id", "limit_check_id");

    private final Downstream down;
    private final Ownership own;
    private final StepUp stepUp;
    private final ActionAudit audit;
    private final ProfileService profile;
    private final ChannelProperties props;

    public PaymentService(Downstream down, Ownership own, StepUp stepUp, ActionAudit audit, ProfileService profile,
                          ChannelProperties props) {
        this.down = down;
        this.own = own;
        this.stepUp = stepUp;
        this.audit = audit;
        this.profile = profile;
        this.props = props;
    }

    // ------------------------------------------------------------------ payees

    public JsonNode payees(Integer limit, String cursor) {
        return down.page(down.get(PAYMENTS, "/v1/payees", Query.of("customer_id", me()).paging(limit, cursor)),
                r -> mine(r, "customer_id") && !"DELETED".equals(r.path("status").asString("")));
    }

    public JsonNode addPayee(JsonNode req, String key) {
        stepUp.require("myPayeesCreate", "PAYEE", null);
        ObjectNode body = down.json().createObjectNode()
                .put("customer_id", me().toString())
                .put("name", required(req, "name"))
                .put("nickname", text(req, "nickname"))
                .put("beneficiary_type", "BUSINESS".equals(text(req, "beneficiary_type")) ? "BUSINESS" : "INDIVIDUAL")
                .put("account_id_type", required(req, "account_id_type"))
                .put("account_number", required(req, "account_number").replaceAll("\\s+", "").toUpperCase())
                .put("bank_name", text(req, "bank_name"))
                .put("agent_bic", text(req, "agent_bic"))
                .put("agent_clearing_id", text(req, "agent_clearing_id"))
                .put("country", required(req, "country").toUpperCase())
                .put("currency", text(req, "currency") == null ? null : text(req, "currency").toUpperCase())
                .put("trusted", false);                 // trust is a separate, stepped-up action
        JsonNode created = down.post(PAYMENTS, "/v1/payees", body, key, "create");
        audit.succeeded("myPayeesCreate", "PAYEE", null, created.path("id").asString(null));
        return created;
    }

    public void removePayee(UUID id) {
        own.payee(id, "myPayeesDelete");
        down.delete(PAYMENTS, "/v1/payees/" + id);
        audit.succeeded("myPayeesDelete", "PAYEE", id, null);
    }

    public JsonNode verifyPayee(UUID id, String key) {
        own.payee(id, "myPayeesVerify");
        return down.post(PAYMENTS, "/v1/payees/" + id + "/verify", null, key, "verify");
    }

    public JsonNode trustPayee(UUID id, String key) {
        own.payee(id, "myPayeesTrust");
        stepUp.require("myPayeesTrust", "PAYEE", id);
        JsonNode r = down.post(PAYMENTS, "/v1/payees/" + id + "/trust", null, key, "trust");
        audit.succeeded("myPayeesTrust", "PAYEE", id, null);
        return r;
    }

    // ------------------------------------------------------------------ payments

    public JsonNode payments(UUID accountId, String status, Integer limit, String cursor) {
        if (accountId != null) {
            own.account(accountId, Access.VIEW, "myPaymentsList");
        }
        ObjectNode page = down.page(down.get(PAYMENTS, "/v1/payments", Query.of("customer_id", me())
                        .with("debtor_account_id", accountId).with("status", status).paging(limit, cursor)),
                r -> mine(r, "customer_id"));
        page.path("data").forEach(PaymentService::forCustomer);
        return page;
    }

    public JsonNode payment(UUID id) {
        return forCustomer(own.payment(id, "myPaymentsGet"));
    }

    /** Strips fraud-control internals (a customer must not learn scores or how a payment was assessed). */
    static JsonNode forCustomer(JsonNode payment) {
        if (payment instanceof ObjectNode o) {
            RISK_INTERNAL.forEach(o::remove);
        }
        return payment;
    }

    public ServiceClient.Download receipt(UUID id) {
        own.payment(id, "myPaymentsReceipt");
        return down.bytes(PAYMENTS, "/v1/payments/" + id + "/receipt");
    }

    public JsonNode validate(JsonNode req, String key) {
        ObjectNode payment = build(req, "myPaymentsValidate");
        return down.post(PAYMENTS, "/v1/payments/validate",
                down.json().createObjectNode().set("payment", payment), key, "validate");
    }

    public JsonNode create(JsonNode req, String key) {
        String action = "myPaymentsCreate";
        ObjectNode payment = build(req, action);
        stepUp.require(action, "ACCOUNT", payment.path("debtor_account_id").asString());
        ObjectNode session = riskContext();
        if (!session.isEmpty()) {
            payment.set("risk_context", session);
        }
        JsonNode created = down.post(PAYMENTS, "/v1/payments", payment, key, "create");
        audit.succeeded(action, "ACCOUNT", payment.path("debtor_account_id").asString(),
                created.path("id").asString(null));
        return forCustomer(created);
    }

    /**
     * Session context for payment-service's fraud scoring, taken server-side from the request: client IP (right-most
     * X-Forwarded-For hop, SEC-16), the app's device fingerprint header and the user agent.
     */
    ObjectNode riskContext() {
        ObjectNode c = down.json().createObjectNode();
        jakarta.servlet.http.HttpServletRequest req = ActionAudit.request();
        if (req == null) {
            return c;
        }
        String ip = ActionAudit.clientIp(req);
        if (ip != null) {
            c.put("ip_address", ip);
        }
        String device = req.getHeader("X-Device-Fingerprint");
        if (device != null && device.matches("[A-Za-z0-9._:+/=-]{8,128}")) {
            c.put("device_fingerprint", device);
        }
        String ua = req.getHeader("User-Agent");
        if (ua != null && !ua.isBlank()) {
            c.put("user_agent", ua.length() > 255 ? ua.substring(0, 255) : ua);
        }
        return c;
    }

    /** The payment as payment-service gets it; nothing identifying or routing comes from the client unchecked. */
    ObjectNode build(JsonNode req, String action) {
        String kind = required(req, "kind");
        UUID from = uuid(req, "from_account_id", true);
        JsonNode debit = own.account(from, Access.TRANSACT, action);
        BigDecimal amount = positive(req, "amount");
        String channel = text(req, "channel");
        ObjectNode p = down.json().createObjectNode()
                .put("channel", channel != null && CHANNELS.contains(channel) ? channel : "WEB")
                .put("priority", "NORMAL")
                .put("customer_id", me().toString())
                .put("debtor_account_id", from.toString())
                .put("amount", amount.toPlainString())
                .put("currency", debit.path("currency").asString())
                .put("reference", text(req, "reference"))
                .put("remittance_unstructured", text(req, "reference"));
        if (props.debtorNameFromProfile()) {
            String name = profile.profile().path("display_name").asString(null);
            if (name != null) {
                p.put("debtor_name", name);
            }
        }
        String date = text(req, "requested_execution_date");
        if (date != null) {
            LocalDate d = LocalDate.parse(date);
            p.put("requested_execution_date", d.toString());
        }
        switch (kind) {
            case "OWN_TRANSFER" -> {
                if (text(req, "payee_id") != null) {
                    throw ApiException.unprocessable("unexpected_payee", "An own transfer has no payee");
                }
                UUID to = uuid(req, "to_account_id", true);
                if (to.equals(from)) {
                    throw ApiException.unprocessable("same_account", "Debit and credit account are the same");
                }
                own.account(to, Access.VIEW, action);
                p.put("payment_type", "INTERNAL_TRANSFER").put("direction", "INTERNAL")
                        .put("creditor_account_id", to.toString());
            }
            case "PAYEE" -> {
                if (text(req, "to_account_id") != null) {
                    throw ApiException.unprocessable("unexpected_account", "A payee payment has no to_account_id");
                }
                UUID payeeId = uuid(req, "payee_id", true);
                JsonNode payee = own.payee(payeeId, action);
                if (!"ACTIVE".equals(payee.path("status").asString(""))) {
                    throw ApiException.unprocessable("payee_not_active", "The payee is not active yet");
                }
                p.put("payment_type", "DOMESTIC").put("direction", "OUTBOUND")
                        .put("payee_id", payeeId.toString())
                        .put("creditor_name", payee.path("name").asString(null))
                        .put("creditor_account_id_type", payee.path("account_id_type").asString(null))
                        .put("creditor_account_number", payee.path("account_number").asString(null))
                        .put("creditor_agent_bic", payee.path("agent_bic").asString(null))
                        .put("creditor_agent_clearing_id", payee.path("agent_clearing_id").asString(null))
                        .put("creditor_country", payee.path("country").asString(null));
            }
            default -> throw ApiException.unprocessable("invalid_kind", "kind must be OWN_TRANSFER or PAYEE");
        }
        return p;
    }
}
