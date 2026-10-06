package io.meridian.collections.infrastructure;

import io.meridian.collections.application.ExposurePort;
import io.meridian.collections.config.CollectionsProperties;
import io.meridian.collections.domain.ArrangementTracker.Payment;
import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** {@link ExposurePort} over loan-service's HTTP API (loans, BNPL loans and credit lines) via {@link ServiceClient}. */
@Component
public class LoanServiceClient implements ExposurePort {
    static final String LOANS = "loan-service";
    private static final int MAX_PAGES = 1000;

    private final ServiceClient client;
    private final CollectionsProperties props;

    public LoanServiceClient(ServiceClient client, CollectionsProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public List<Exposure> delinquent() {
        List<Exposure> out = new ArrayList<>();
        for (JsonNode l : pages("/v1/loans?status=IN_ARREARS")) {
            out.add(loan(l));
        }
        for (JsonNode c : pages("/v1/credit-lines?status=IN_ARREARS")) {
            out.add(creditLine(c));
        }
        return out;
    }

    @Override
    public Optional<Exposure> get(String exposureType, UUID id) {
        try {
            return switch (exposureType) {
                case "LOAN", "BNPL" -> Optional.of(loan(client.get(LOANS, "/v1/loans/" + id)));
                case "CREDIT_LINE" -> Optional.of(creditLine(client.get(LOANS, "/v1/credit-lines/" + id)));
                default -> Optional.empty();
            };
        } catch (ApiException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public List<Payment> payments(String exposureType, UUID id) {
        String base = switch (exposureType) {
            case "LOAN", "BNPL" -> "/v1/loans/" + id + "/repayments";
            case "CREDIT_LINE" -> "/v1/loan-repayments?credit_line_id=" + id;
            default -> null;
        };
        if (base == null) {
            return List.of();
        }
        List<Payment> out = new ArrayList<>();
        for (JsonNode r : pages(base)) {
            String vd = text(r, "value_date");
            if (vd == null) {
                String rec = text(r, "received_at");
                vd = rec == null ? null : rec.substring(0, 10);
            }
            out.add(new Payment(UUID.fromString(r.get("id").asString()), dec(r, "amount"),
                    vd == null ? null : LocalDate.parse(vd.substring(0, 10)), text(r, "status"),
                    text(r, "reversal_of_id") == null ? null : UUID.fromString(text(r, "reversal_of_id"))));
        }
        return out;
    }

    private List<JsonNode> pages(String path) {
        List<JsonNode> out = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < MAX_PAGES; i++) {
            String sep = path.contains("?") ? "&" : "?";
            String url = path + sep + "limit=" + props.syncPageLimit()
                    + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            JsonNode page = client.get(LOANS, url);
            page.path("data").forEach(out::add);
            cursor = page.path("next_cursor").isString() ? page.get("next_cursor").asString() : null;
            if (!page.path("has_more").asBoolean(false) || cursor == null) {
                break;
            }
        }
        return out;
    }

    private static Exposure loan(JsonNode l) {
        String type = "BNPL".equals(text(l, "loan_type")) ? "BNPL" : "LOAN";
        return new Exposure(UUID.fromString(l.get("id").asString()), type, uuid(l, "customer_id"), uuid(l, "product_id"),
                text(l, "risk_grade"), text(l, "status"), l.path("days_past_due").asInt(0), dec(l, "overdue_amount"),
                dec(l, "total_outstanding"), text(l, "currency"));
    }

    /** Credit lines report no arrears amount: the whole balance (principal, interest, fees) is the exposure. */
    private static Exposure creditLine(JsonNode c) {
        BigDecimal total = dec(c, "utilised_amount").add(dec(c, "outstanding_interest")).add(dec(c, "outstanding_fees"));
        BigDecimal overdue = c.hasNonNull("overdue_amount") ? dec(c, "overdue_amount") : total;
        return new Exposure(UUID.fromString(c.get("id").asString()), "CREDIT_LINE", uuid(c, "customer_id"),
                uuid(c, "product_id"), text(c, "risk_grade"), text(c, "status"), c.path("days_past_due").asInt(0),
                overdue, total, text(c, "currency"));
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static UUID uuid(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : UUID.fromString(s);
    }

    private static BigDecimal dec(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? BigDecimal.ZERO : new BigDecimal(s);
    }
}
