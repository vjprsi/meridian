package io.meridian.routing.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.routing.config.RoutingProperties;
import io.meridian.routing.domain.CutoffCalculator;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Calls to other Meridian services through {@link ServiceClient} (tenant, user and token propagated):
 * ledger-service (postings, balances), payment-service (inbound rail events) and reference-data-service
 * (business calendars). Every write carries a deterministic idempotency key.
 */
@Component
public class Downstream {
    public static final String LEDGER = "ledger-service";
    public static final String PAYMENTS = "payment-service";
    public static final String REFDATA = "reference-data-service";

    private final ServiceClient client;
    private final RoutingProperties props;
    private final JsonMapper json;

    public Downstream(ServiceClient client, RoutingProperties props, JsonMapper json) {
        this.client = client;
        this.props = props;
        this.json = json;
    }

    // ------------------------------------------------------------------ ledger

    /** Posts a balanced two-line journal entry: debit {@code debit}, credit {@code credit}. Returns its id. */
    public UUID transfer(String key, UUID debit, UUID credit, BigDecimal amount, String currency, String description,
                         String sourceType, UUID sourceId) {
        if (props.ledgerId() == null) {
            throw new ApiException(500, "ledger_not_configured", "meridian.routing.ledger-id is not configured");
        }
        ObjectNode body = json.createObjectNode();
        body.put("ledger_id", props.ledgerId().toString());
        body.put("entry_type", "TRANSFER");
        body.put("description", description);
        body.put("source_type", sourceType);
        body.put("source_id", sourceId.toString());
        var entries = body.putArray("entries");
        entries.addObject().put("ledger_account_id", debit.toString()).put("direction", "DEBIT")
                .put("amount", amount.toPlainString()).put("currency", currency);
        entries.addObject().put("ledger_account_id", credit.toString()).put("direction", "CREDIT")
                .put("amount", amount.toPlainString()).put("currency", currency);
        JsonNode r = client.post(LEDGER, "/v1/journal-entries", body, key);
        String id = r.path("id").asString(null);
        if (id == null) {
            throw new ApiException(502, "upstream_error", "ledger-service returned no journal entry id");
        }
        return UUID.fromString(id);
    }

    public JsonNode balance(UUID ledgerAccountId) {
        return client.get(LEDGER, "/v1/ledger-accounts/" + ledgerAccountId + "/balance");
    }

    // ------------------------------------------------------------------ payment-service

    public Optional<JsonNode> paymentByEndToEndId(String endToEndId) {
        JsonNode page = client.get(PAYMENTS, "/v1/payments?limit=1&end_to_end_id=" + enc(endToEndId));
        JsonNode data = page.path("data");
        return data.isArray() && !data.isEmpty() ? Optional.of(data.get(0)) : Optional.empty();
    }

    public JsonNode post(String service, String path, ObjectNode body, String key) {
        return client.post(service, path, body, key);
    }

    // ------------------------------------------------------------------ reference data

    /** Business calendar backed by reference-data-service; weekends-only when the rail has no calendar. */
    public CutoffCalculator.BusinessCalendar calendar(UUID holidayCalendarId) {
        if (holidayCalendarId == null) {
            return CutoffCalculator.WEEKENDS_ONLY;
        }
        String base = "/v1/reference/holiday-calendars/" + holidayCalendarId;
        return new CutoffCalculator.BusinessCalendar() {
            @Override
            public boolean isBusinessDay(LocalDate date) {
                return client.get(REFDATA, base + "/is-business-day?date=" + date).path("is_business_day").asBoolean(false);
            }

            @Override
            public LocalDate nextBusinessDay(LocalDate date) {
                String d = client.get(REFDATA, base + "/next-business-day?date=" + date + "&days=1")
                        .path("business_day").asString(null);
                if (d == null) {
                    throw new ApiException(502, "upstream_error", "reference-data-service returned no business_day");
                }
                return LocalDate.parse(d);
            }
        };
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
