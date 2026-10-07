package io.meridian.reconciliation.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.reconciliation.domain.Matcher;
import io.meridian.reconciliation.domain.StatementLine;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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

    /**
     * The same entries as engine items, with the payment references ledger-service copies onto every ledger entry
     * (RECON-REAL-REFS): UETR, end-to-end id, instruction id, bank (clearing-system) reference, scheme / rail reference,
     * batch id, counterparty name and the keyed pseudonym of the counterparty account, plus the journal reference,
     * the line narrative and whether the entry is a ledger reversal. Postings made before payment references existed
     * carry only the reference, which the engine also indexes as end-to-end id / instruction id / bank reference.
     */
    public List<io.meridian.reconciliation.domain.matching.InternalItem> items(UUID ledgerAccountId, LocalDate from,
                                                                               LocalDate to) {
        List<io.meridian.reconciliation.domain.matching.InternalItem> out = new ArrayList<>();
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
                    out.add(item(i, e));
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

    /** Engine item of a ledger entry row (fields as returned by GET /v1/ledger-entries). */
    static io.meridian.reconciliation.domain.matching.InternalItem item(Matcher.Internal i, JsonNode e) {
        String batch = text(e, "batch_id") != null ? text(e, "batch_id") : text(e, "batch_ref");
        String account = text(e, "counterparty_account_hash") != null ? text(e, "counterparty_account_hash")
                : text(e, "counterparty_account");
        return new io.meridian.reconciliation.domain.matching.InternalItem(i.id(), i.valueDate(), i.direction(),
                i.amount(), i.currency(), i.reference(), text(e, "end_to_end_id"), text(e, "instruction_id"),
                text(e, "uetr"), batch, text(e, "counterparty_name"), account, text(e, "description"),
                "REVERSAL".equals(text(e, "entry_type")), text(e, "bank_reference"), text(e, "scheme_reference"));
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) && !n.get(field).asString().isBlank() ? n.get(field).asString() : null;
    }

    /** A ledger entry carries the value date and the reference of its journal entry (copied at posting time). */
    static Matcher.Internal toInternal(JsonNode e) {
        LocalDate valueDate = LocalDate.parse(e.get("value_date").asString());
        String reference = e.hasNonNull("reference") && !e.get("reference").asString().isBlank()
                ? e.get("reference").asString() : null;
        return new Matcher.Internal(UUID.fromString(e.get("id").asString()), reference, valueDate,
                StatementLine.Direction.valueOf(e.get("direction").asString()),
                new BigDecimal(e.get("amount").asString()), e.get("currency").asString().trim());
    }
}
