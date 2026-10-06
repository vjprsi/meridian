package io.meridian.liquidity.infrastructure;

import io.meridian.liquidity.domain.CashFlow;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Expected cash flows from other services, via {@link ServiceClient} (tenant, user and token propagated):
 * contractual flows of live treasury deals (treasury-deal-service projected-flows) and scheduled payments not yet
 * final (payment-service, by requested execution date).
 */
@Component
public class FlowSources {
    static final String TREASURY = "treasury-deal-service";
    static final String PAYMENTS = "payment-service";
    /** Payment statuses whose cash has already moved or never will. */
    static final Set<String> FINAL_PAYMENT_STATUSES = Set.of("SETTLED", "REJECTED", "RETURNED", "FAILED", "CANCELLED");
    static final int PAYMENT_PAGE = 200;
    static final int MAX_PAYMENT_PAGES = 50;

    private final ServiceClient client;

    public FlowSources(ServiceClient client) {
        this.client = client;
    }

    /** Treasury deal flows with value dates in [from, to], optionally for one legal entity. */
    public List<CashFlow> treasuryFlows(LocalDate from, LocalDate to, UUID legalEntityId) {
        String path = "/v1/treasury/settlements/projected-flows?from=" + from + "&to=" + to
                + (legalEntityId == null ? "" : "&legal_entity_id=" + legalEntityId);
        JsonNode body = client.get(TREASURY, path);
        List<CashFlow> out = new ArrayList<>();
        JsonNode flows = body == null ? null : body.get("flows");
        if (flows == null || !flows.isArray()) {
            return out;
        }
        for (JsonNode f : flows) {
            BigDecimal amount = Rows.dec(f, "amount");
            LocalDate valueDate = Rows.date(f, "value_date");
            String currency = Rows.str(f, "currency");
            String direction = Rows.str(f, "direction");
            if (amount == null || valueDate == null || currency == null || direction == null || amount.signum() == 0) {
                continue;
            }
            boolean in = direction.toUpperCase(Locale.ROOT).startsWith("IN") || "DEBIT".equalsIgnoreCase(direction);
            out.add(new CashFlow(valueDate, currency, in, amount.abs(),
                    CashFlow.category(Rows.str(f, "category"), "TREASURY_DEALS"),
                    Rows.uuid(f, "nostro_account_id"), Rows.str(f, "flow_type")));
        }
        return out;
    }

    /** Scheduled payments for one execution date that have not reached a final status. */
    public List<CashFlow> paymentFlows(LocalDate date) {
        List<CashFlow> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAYMENT_PAGES; page++) {
            String path = "/v1/payments?requested_execution_date=" + date + "&limit=" + PAYMENT_PAGE
                    + (cursor == null ? "" : "&cursor=" + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8));
            JsonNode body = client.get(PAYMENTS, path);
            JsonNode data = body == null ? null : body.get("data");
            if (data == null || !data.isArray()) {
                break;
            }
            for (JsonNode p : data) {
                String status = Rows.str(p, "status");
                String direction = Rows.str(p, "direction");
                BigDecimal amount = Rows.dec(p, "amount");
                String currency = Rows.str(p, "currency");
                if (status != null && FINAL_PAYMENT_STATUSES.contains(status) || amount == null || currency == null
                        || direction == null || "INTERNAL".equals(direction)) {
                    continue;
                }
                out.add(new CashFlow(date, currency, "INBOUND".equals(direction), amount.abs(), "PAYMENTS",
                        null, "PAYMENT"));
            }
            JsonNode more = body.get("has_more");
            cursor = Rows.str(body, "next_cursor");
            if (more == null || !more.asBoolean() || cursor == null) {
                break;
            }
        }
        return out;
    }
}
