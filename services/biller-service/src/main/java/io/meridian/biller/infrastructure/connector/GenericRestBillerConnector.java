package io.meridian.biller.infrastructure.connector;

import io.meridian.biller.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Generic REST biller / aggregator connector for bill inquiry and payment (and airtime top-up) over HTTP(S), with a
 * configurable request and response mapping so one connector serves many partners. Activated for a biller whose
 * {@code integration_type} is {@code API} or {@code AGGREGATOR} and whose {@code connection_config} carries a
 * {@code base_url}. Resilience (timeouts, retry/backoff, circuit breaker) and auth come from {@link AbstractRestConnector}.
 *
 * <p>Request fields default to {@code accountReference}, {@code amount}, {@code currency}, {@code msisdn},
 * {@code billerCode}, {@code clientReference}; each can be renamed and extra constant fields added via
 * {@code connection_config.request}. The outcome is read from {@code connection_config.response}:
 * {@code code_path} (default {@code responseCode}) is matched against {@code success_values} (default
 * {@code 00,0,SUCCESS,OK,APPROVED,ACCEPTED}); the provider reference, token, receipt, customer and amount are read
 * from their configured paths.
 */
@Component
@Order(200)
public class GenericRestBillerConnector extends AbstractRestConnector {
    private static final Set<String> DEFAULT_SUCCESS = Set.of("00", "0", "SUCCESS", "OK", "APPROVED", "ACCEPTED");

    public GenericRestBillerConnector(ConnectorTransport transport, CircuitBreakerRegistry breakers,
                                      BillerSecrets secrets, JsonMapper json) {
        super(transport, breakers, secrets, json);
    }

    @Override
    public boolean supports(JsonNode biller) {
        if (biller == null) {
            return false;
        }
        String type = Rows.str(biller, "integration_type");
        if (!"API".equals(type) && !"AGGREGATOR".equals(type)) {
            return false;
        }
        return new ConnectorConfig(biller).present();
    }

    @Override
    public Inquiry inquire(JsonNode biller, String accountReference) {
        ConnectorConfig cfg = new ConnectorConfig(biller);
        if (!cfg.present()) {
            return new Inquiry("91", "Biller connector not configured", null, null, null, null);
        }
        ObjectNode body = baseBody(cfg, biller);
        body.put(cfg.request().path("reference_field").asString("accountReference"), accountReference);
        String url = cfg.baseUrl() + cfg.endpoint("inquiry", "/inquiries");
        Exchange ex = call(cfg, providerKey(biller), null, "POST", url, authHeaders(cfg), body);
        if (ex instanceof Unanswered u) {
            // 91 = issuer inoperative: not found, but not a definitive "no such account" either
            return new Inquiry("91", u.message(), null, null, null, null);
        }
        Answered a = (Answered) ex;
        if (a.status() / 100 != 2 || !matchesSuccess(cfg, a.body())) {
            String msg = textAt(a.body(), cfg.response().path("message_path").asString("message"));
            return new Inquiry("14", msg == null ? "Account not found at the biller" : msg, null, null, null, null);
        }
        JsonNode b = a.body();
        String name = textAt(b, cfg.response().path("customer_name_path").asString("customerName"));
        BigDecimal due = decAt(b, cfg.response().path("amount_due_path").asString("amountDue"));
        var dueDate = dateAt(b, cfg.response().path("due_date_path").asString("dueDate"));
        String ref = textAt(b, cfg.response().path("reference_path").asString("reference"));
        return new Inquiry("00", "OK", name,
                due == null ? BigDecimal.ZERO.setScale(2) : due,
                dueDate == null ? java.time.LocalDate.now() : dueDate,
                ref == null ? "INQ-" + providerKey(biller) : ref);
    }

    @Override
    public Result pay(JsonNode biller, JsonNode payment, int attempt) {
        ConnectorConfig cfg = new ConnectorConfig(biller);
        if (!cfg.present()) {
            return new Result(Outcome.REJECTED, null, null, null, "Biller connector not configured");
        }
        ObjectNode body = payBody(cfg, biller, payment);
        String url = cfg.baseUrl() + cfg.endpoint("pay", "/payments");
        Exchange ex = call(cfg, providerKey(biller), idempotencyKey(payment, attempt), "POST", url, authHeaders(cfg), body);
        return toResult(cfg, ex, providerKey(biller));
    }

    @Override
    public Result topup(JsonNode biller, JsonNode topup, int attempt) {
        ConnectorConfig cfg = new ConnectorConfig(biller);
        if (!cfg.present()) {
            return new Result(Outcome.REJECTED, null, null, null, "Biller connector not configured");
        }
        ObjectNode body = topupBody(cfg, biller, topup);
        String url = cfg.baseUrl() + cfg.endpoint("topup", "/topups");
        Exchange ex = call(cfg, providerKey(biller), idempotencyKey(topup, attempt), "POST", url, authHeaders(cfg), body);
        return toResult(cfg, ex, providerKey(biller));
    }

    // ------------------------------------------------------------------ request / response mapping

    private ObjectNode baseBody(ConnectorConfig cfg, JsonNode biller) {
        ObjectNode b = json.createObjectNode();
        JsonNode extra = cfg.request().path("extra");
        if (extra.isObject()) {
            for (Map.Entry<String, JsonNode> e : extra.properties()) {
                b.set(e.getKey(), e.getValue());
            }
        }
        String code = Rows.str(biller, "biller_code");
        if (code != null) {
            b.put(cfg.request().path("biller_code_field").asString("billerCode"), code);
        }
        return b;
    }

    private ObjectNode payBody(ConnectorConfig cfg, JsonNode biller, JsonNode payment) {
        JsonNode req = cfg.request();
        ObjectNode b = baseBody(cfg, biller);
        putNonNull(b, req.path("reference_field").asString("accountReference"), Rows.str(payment, "account_reference"));
        putAmount(b, req, payment);
        putNonNull(b, req.path("currency_field").asString("currency"), Rows.str(payment, "currency"));
        putNonNull(b, req.path("client_reference_field").asString("clientReference"), Rows.str(payment, "id"));
        if (payment.hasNonNull("customer_name")) {
            b.put(req.path("customer_name_field").asString("customerName"), Rows.str(payment, "customer_name"));
        }
        return b;
    }

    private ObjectNode topupBody(ConnectorConfig cfg, JsonNode biller, JsonNode topup) {
        JsonNode req = cfg.request();
        ObjectNode b = baseBody(cfg, biller);
        putNonNull(b, req.path("msisdn_field").asString("msisdn"), Rows.str(topup, "target_msisdn"));
        putAmount(b, req, topup);
        putNonNull(b, req.path("currency_field").asString("currency"), Rows.str(topup, "currency"));
        putNonNull(b, req.path("client_reference_field").asString("clientReference"), Rows.str(topup, "id"));
        return b;
    }

    private static void putAmount(ObjectNode b, JsonNode req, JsonNode row) {
        BigDecimal amt = Rows.dec(row, "amount");
        if (amt == null) {
            return;
        }
        String field = req.path("amount_field").asString("amount");
        if (req.path("amount_as_number").asBoolean(false)) {
            b.put(field, amt);
        } else {
            b.put(field, amt.toPlainString());
        }
    }

    private static void putNonNull(ObjectNode b, String field, String value) {
        if (value != null) {
            b.put(field, value);
        }
    }

    private static String idempotencyKey(JsonNode row, int attempt) {
        return Rows.str(row, "id") + ":" + attempt;
    }

    private Result toResult(ConnectorConfig cfg, Exchange ex, String providerKey) {
        if (ex instanceof Unanswered u) {
            return new Result(u.timeout() ? Outcome.TIMEOUT : Outcome.REJECTED, null, null, null, u.message());
        }
        Answered a = (Answered) ex;
        JsonNode body = a.body();
        String msg = textAt(body, cfg.response().path("message_path").asString("message"));
        if (a.status() / 100 == 2 && matchesSuccess(cfg, body)) {
            String ref = textAt(body, cfg.response().path("provider_reference_path").asString("providerReference"));
            if (ref == null) {
                ref = textAt(body, cfg.response().path("reference_path").asString("reference"));
            }
            if (ref == null) {
                ref = providerKey + "-" + a.status();
            }
            String token = textAt(body, cfg.response().path("token_path").asString("token"));
            String receipt = textAt(body, cfg.response().path("receipt_path").asString("receiptNumber"));
            return new Result(Outcome.SUCCESS, ref, token, receipt, msg == null ? "Accepted by the provider" : msg);
        }
        return new Result(Outcome.REJECTED, null, null, null,
                msg == null ? "Rejected by the provider (HTTP " + a.status() + ")" : msg);
    }

    private boolean matchesSuccess(ConnectorConfig cfg, JsonNode body) {
        JsonNode code = at(body, cfg.response().path("code_path").asString("responseCode"));
        if (code.isMissingNode() || code.isNull()) {
            return true; // no explicit result code: a 2xx is success
        }
        return successValues(cfg).contains(code.asString().trim().toUpperCase(Locale.ROOT));
    }

    private Set<String> successValues(ConnectorConfig cfg) {
        JsonNode vals = cfg.response().path("success_values");
        if (vals.isArray() && !vals.isEmpty()) {
            Set<String> s = new HashSet<>();
            vals.forEach(v -> s.add(v.asString().trim().toUpperCase(Locale.ROOT)));
            return s;
        }
        return DEFAULT_SUCCESS;
    }
}
