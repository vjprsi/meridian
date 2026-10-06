package io.meridian.fx.infrastructure;

import io.meridian.common.error.ApiException;
import io.meridian.web.client.ServiceClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Posts FX journal entries to ledger-service; every posting carries a deterministic idempotency key. */
@Component
public class LedgerClient {
    static final String LEDGER = "ledger-service";

    public record Leg(UUID account, String direction, BigDecimal amount, String currency, String description) {}

    private final ServiceClient client;
    private final JsonMapper json;

    public LedgerClient(ServiceClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    public UUID post(UUID ledgerId, String key, String description, String sourceType, UUID sourceId,
                     LocalDate valueDate, List<Leg> legs) {
        if (ledgerId == null) {
            throw new ApiException(500, "ledger_not_configured", "meridian.fx.ledger-id is not configured");
        }
        ObjectNode body = json.createObjectNode();
        body.put("ledger_id", ledgerId.toString());
        body.put("entry_type", "FX");
        body.put("description", description);
        body.put("source_type", sourceType);
        body.put("source_id", sourceId.toString());
        if (valueDate != null) {
            body.put("value_date", valueDate.toString());
        }
        ArrayNode entries = body.putArray("entries");
        for (Leg l : legs) {
            ObjectNode e = entries.addObject();
            e.put("ledger_account_id", l.account().toString());
            e.put("direction", l.direction());
            e.put("amount", l.amount().toPlainString());
            e.put("currency", l.currency());
            if (l.description() != null) {
                e.put("description", l.description());
            }
        }
        JsonNode r = client.post(LEDGER, "/v1/journal-entries", body, key);
        String id = r == null ? null : r.path("id").asString(null);
        if (id == null) {
            throw new ApiException(502, "upstream_error", "ledger-service returned no journal entry id");
        }
        return UUID.fromString(id);
    }
}
