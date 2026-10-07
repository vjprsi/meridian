package io.meridian.treasurydeal.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.treasurydeal.application.Ports;
import io.meridian.treasurydeal.config.TreasuryProperties;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adapters to other Meridian services over {@link ServiceClient} (tenant, user and token propagated):
 * ledger-service journal entries, payment-service outgoing payments and reference-data FX rates.
 */
@Component
public class ExternalServices implements Ports.Ledger, Ports.Payments, Ports.MarketData {
    static final String LEDGER = "ledger-service";
    static final String PAYMENTS = "payment-service";
    static final String REFERENCE = "reference-data-service";

    private final ServiceClient client;
    private final TreasuryProperties props;
    private final JsonMapper json;

    public ExternalServices(ServiceClient client, TreasuryProperties props, JsonMapper json) {
        this.client = client;
        this.props = props;
        this.json = json;
    }

    @Override
    public UUID post(Ports.Posting p) {
        UUID ledgerId = props.ledgerId();
        if (ledgerId == null) {
            throw ApiException.unprocessable("ledger_not_configured", "No ledger book is configured for this tenant "
                    + "(config-service ledger/treasury-deal-service.ledger_id or meridian.treasury.ledger-id)");
        }
        // net lines per account and direction so one account never appears twice on the same side
        Map<String, BigDecimal> net = new LinkedHashMap<>();
        Map<String, Ports.Line> sample = new LinkedHashMap<>();
        for (Ports.Line l : p.lines()) {
            if (l.amount() == null || l.amount().signum() == 0) {
                continue;
            }
            if (l.amount().signum() < 0) {
                throw new IllegalArgumentException("posting lines must be positive: " + l);
            }
            String k = l.currency() + "|" + l.role() + "|" + l.direction();
            net.merge(k, l.amount(), BigDecimal::add);
            sample.putIfAbsent(k, l);
        }
        ObjectNode body = json.createObjectNode();
        body.put("ledger_id", ledgerId.toString());
        body.put("entry_type", p.entryType());
        body.put("description", p.description().length() > 500 ? p.description().substring(0, 500) : p.description());
        body.put("source_type", p.sourceType());
        body.put("source_id", p.sourceId().toString());
        if (p.valueDate() != null) {
            body.put("value_date", p.valueDate().toString());
        }
        if (p.reference() != null) {
            body.put("reference", p.reference());
        }
        ArrayNode entries = body.putArray("entries");
        net.forEach((k, amount) -> {
            Ports.Line l = sample.get(k);
            UUID account = props.account(l.currency(), l.role());
            if (account == null) {
                throw ApiException.unprocessable("ledger_account_not_configured",
                        "No ledger account configured for " + l.role() + " in " + l.currency() + " (config-service ledger/"
                                + "treasury-deal-service.accounts." + l.currency() + "." + l.role().replace('-', '_')
                                + " or meridian.treasury.accounts." + l.currency() + "." + l.role() + ")");
            }
            ObjectNode e = entries.addObject();
            e.put("ledger_account_id", account.toString());
            e.put("direction", l.direction());
            e.put("amount", amount.toPlainString());
            e.put("currency", l.currency());
            if (l.description() != null) {
                e.put("description", l.description());
            }
        });
        return id(client.post(LEDGER, "/v1/journal-entries", body, p.key()), LEDGER);
    }

    @Override
    public UUID create(Ports.PaymentOrder o) {
        ObjectNode body = json.createObjectNode();
        body.put("payment_type", "CROSS_BORDER");
        body.put("direction", "OUTBOUND");
        body.put("channel", "API");
        body.put("priority", "HIGH");
        body.put("creditor_name", o.creditorName().length() > 140 ? o.creditorName().substring(0, 140) : o.creditorName());
        body.put("creditor_account_id_type", o.creditorAccount().matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{8,30}") ? "IBAN" : "ACCOUNT_NUMBER");
        body.put("creditor_account_number", o.creditorAccount());
        body.put("creditor_agent_bic", o.creditorBic());
        if (o.countryCode() != null) {
            body.put("creditor_country", o.countryCode());
        }
        body.put("amount", o.amount().toPlainString());
        body.put("currency", o.currency());
        body.put("charge_bearer", "SHAR");
        body.put("end_to_end_id", o.endToEndId());
        body.put("requested_execution_date", o.executionDate().toString());
        body.put("reference", o.reference());
        body.put("category_purpose", "TREA");
        body.put("remittance_unstructured", "Treasury settlement " + o.reference()
                + (o.intermediaryBic() == null ? "" : " via " + o.intermediaryBic()));
        if (o.debtorLedgerAccountId() != null) {
            body.put("debtor_ledger_account_id", o.debtorLedgerAccountId().toString());
        }
        return id(client.post(PAYMENTS, "/v1/payments", body, o.key()), PAYMENTS);
    }

    @Override
    public Optional<BigDecimal> rate(String base, String quote, String type) {
        try {
            JsonNode r = client.get(REFERENCE, "/v1/reference/fx-rates/latest?base=" + enc(base) + "&quote=" + enc(quote)
                    + "&type=" + enc(type));
            String v = r.path("rate").asString(null);
            return v == null ? Optional.empty() : Optional.of(new BigDecimal(v));
        } catch (ApiException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static UUID id(JsonNode node, String service) {
        String id = node == null ? null : node.path("id").asString(null);
        if (id == null) {
            throw new ApiException(502, "upstream_error", service + " returned no id");
        }
        return UUID.fromString(id);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
