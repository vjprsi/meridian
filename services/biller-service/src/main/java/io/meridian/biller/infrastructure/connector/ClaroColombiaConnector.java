package io.meridian.biller.infrastructure.connector;

import io.meridian.biller.infrastructure.Rows;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reference connector for Claro Colombia airtime top-ups over REST. It is concrete (fixed request shape and response
 * mapping for Claro's recharge API) but fully configuration-driven: the endpoint and credentials come from the
 * biller's {@code connection_config}, never from code.
 *
 * <p>Activated for a biller with {@code integration_type = AGGREGATOR} and {@code aggregator_name} in
 * {@code CLARO_CO} / {@code CLARO} / {@code CLARO_COLOMBIA} that has a {@code base_url}. The top-up request is
 * {@code POST {base_url}{endpoints.topup | /airtime/v1/recharges}} with
 * {@code {msisdn, amount, currency, distributorId?, productId?, clientReference}}; the response is read as
 * {@code status} (success when 2xx and {@code APPROVED/SUCCESS/OK/00/COMPLETED}, overridable via
 * {@code response.success_values}) with the provider reference from {@code transactionId / authorizationCode /
 * reference / id}. Resilience, auth and idempotency come from {@link AbstractRestConnector}.
 *
 * <p>Claro airtime does not offer bill inquiry or bill payment, so those paths return a graceful non-fatal result
 * (they are never reached for an airtime biller: inquiry is gated by {@code supports_inquiry}, and airtime uses the
 * top-up path).
 */
@Component
@Order(100)
public class ClaroColombiaConnector extends AbstractRestConnector {
    private static final Set<String> AGGREGATORS = Set.of("CLARO_CO", "CLARO", "CLARO_COLOMBIA");
    private static final Set<String> DEFAULT_SUCCESS = Set.of("APPROVED", "SUCCESS", "OK", "00", "0", "COMPLETED");

    public ClaroColombiaConnector(ConnectorTransport transport, CircuitBreakerRegistry breakers,
                                  BillerSecrets secrets, JsonMapper json) {
        super(transport, breakers, secrets, json);
    }

    @Override
    public boolean supports(JsonNode biller) {
        if (biller == null || !"AGGREGATOR".equals(Rows.str(biller, "integration_type"))) {
            return false;
        }
        String agg = Rows.str(biller, "aggregator_name");
        if (agg == null || !AGGREGATORS.contains(agg.trim().toUpperCase(Locale.ROOT))) {
            return false;
        }
        return new ConnectorConfig(biller).present();
    }

    @Override
    public Result topup(JsonNode biller, JsonNode topup, int attempt) {
        ConnectorConfig cfg = new ConnectorConfig(biller);
        if (!cfg.present()) {
            return new Result(Outcome.REJECTED, null, null, null, "Claro connector is not configured");
        }
        ObjectNode body = json.createObjectNode();
        putNonNull(body, "msisdn", normalizeMsisdn(Rows.str(topup, "target_msisdn")));
        BigDecimal amt = Rows.dec(topup, "amount");
        if (amt != null) {
            body.put("amount", amt.toPlainString());
        }
        putNonNull(body, "currency", Rows.str(topup, "currency"));
        putNonNull(body, "distributorId", cfg.node().path("distributor_id").asString(null));
        if (topup.hasNonNull("biller_product_id")) {
            body.put("productId", Rows.str(topup, "biller_product_id"));
        }
        putNonNull(body, "clientReference", Rows.str(topup, "id"));

        String url = cfg.baseUrl() + cfg.endpoint("topup", "/airtime/v1/recharges");
        Exchange ex = call(cfg, providerKey(biller), Rows.str(topup, "id") + ":" + attempt, "POST", url,
                authHeaders(cfg), body);
        if (ex instanceof Unanswered u) {
            return new Result(u.timeout() ? Outcome.TIMEOUT : Outcome.REJECTED, null, null, null, u.message());
        }
        Answered a = (Answered) ex;
        JsonNode b = a.body();
        String status = firstText(b, "status", "resultCode", "responseCode");
        String providerRef = firstText(b, "transactionId", "authorizationCode", "reference", "id");
        String message = firstText(b, "message", "description", "resultDescription");
        if (a.status() / 100 == 2 && isSuccess(cfg, status)) {
            return new Result(Outcome.SUCCESS, providerRef != null ? providerRef : "CLARO-" + a.status(), null, null,
                    message != null ? message : "Recharge accepted by Claro");
        }
        return new Result(Outcome.REJECTED, null, null, null, message != null ? message
                : "Claro declined the recharge" + (status != null ? " (" + status + ")" : ""));
    }

    @Override
    public Inquiry inquire(JsonNode biller, String accountReference) {
        return new Inquiry("30", "Claro airtime does not support bill inquiry", null, null, null, null);
    }

    @Override
    public Result pay(JsonNode biller, JsonNode payment, int attempt) {
        return new Result(Outcome.REJECTED, null, null, null, "Claro is an airtime operator; use the top-up path");
    }

    private boolean isSuccess(ConnectorConfig cfg, String status) {
        if (status == null) {
            return true; // a 2xx with no explicit status is treated as accepted
        }
        JsonNode vals = cfg.response().path("success_values");
        Set<String> ok;
        if (vals.isArray() && !vals.isEmpty()) {
            ok = new HashSet<>();
            vals.forEach(v -> ok.add(v.asString().trim().toUpperCase(Locale.ROOT)));
        } else {
            ok = DEFAULT_SUCCESS;
        }
        return ok.contains(status.trim().toUpperCase(Locale.ROOT));
    }

    private static String normalizeMsisdn(String m) {
        return m == null ? null : m.replaceAll("[^0-9]", "");
    }

    private static void putNonNull(ObjectNode b, String field, String value) {
        if (value != null) {
            b.put(field, value);
        }
    }
}
