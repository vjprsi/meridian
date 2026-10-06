package io.meridian.reconciliation.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.reconciliation.domain.Matcher;
import io.meridian.reconciliation.domain.StatementLine;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Internal side of the reconciliation: ledger entries of one ledger account, read from ledger-service. */
@Component
public class LedgerGateway {
    static final int PAGE_SIZE = 200;
    static final int MAX_PAGES = 100;

    private final ServiceClient services;

    public LedgerGateway(ServiceClient services) {
        this.services = services;
    }

    /** Ledger entries of the account with a value date in [from, to], oldest first. */
    public List<Matcher.Internal> entries(UUID ledgerAccountId, LocalDate from, LocalDate to) {
        List<Matcher.Internal> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String path = "/v1/ledger-entries?ledger_account_id=" + ledgerAccountId + "&limit=" + PAGE_SIZE
                    + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            JsonNode body = services.get("ledger-service", path);
            if (body == null || !body.path("data").isArray()) {
                throw new ApiException(502, "upstream_error", "ledger-service returned no ledger entry page");
            }
            for (JsonNode e : body.path("data")) {
                Matcher.Internal i = toInternal(e);
                if (!i.valueDate().isBefore(from) && !i.valueDate().isAfter(to)) {
                    out.add(i);
                }
            }
            if (!body.path("has_more").asBoolean(false) || !body.hasNonNull("next_cursor")) {
                return out;
            }
            cursor = body.get("next_cursor").asString();
        }
        throw new ApiException(502, "upstream_error", "ledger-service returned more than "
                + PAGE_SIZE * MAX_PAGES + " entries for account " + ledgerAccountId + "; narrow the run's date range");
    }

    static Matcher.Internal toInternal(JsonNode e) {
        LocalDate valueDate = e.hasNonNull("value_date") ? LocalDate.parse(e.get("value_date").asString())
                : OffsetDateTime.parse(e.get("effective_at").asString()).toLocalDate();
        String reference = null;
        for (String f : List.of("reference", "end_to_end_id", "correlation_id", "external_ref", "description")) {
            if (e.hasNonNull(f) && !e.get(f).asString().isBlank()) {
                reference = e.get(f).asString();
                break;
            }
        }
        return new Matcher.Internal(UUID.fromString(e.get("id").asString()), reference, valueDate,
                StatementLine.Direction.valueOf(e.get("direction").asString()),
                new BigDecimal(e.get("amount").asString()), e.get("currency").asString().trim());
    }
}
