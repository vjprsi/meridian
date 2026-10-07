package io.meridian.productcatalog.application;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Benchmark indexes: append-only dated fixings; publishing one triggers repricing downstream. */
@Service
public class RateIndexService {
    static final BigDecimal MAX_ABS_RATE = BigDecimal.ONE;

    private final CatalogStore store;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public RateIndexService(CatalogStore store, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String publishValue(UUID indexId, LocalDate valueDate, BigDecimal rate, String sourceRef) {
        JsonNode index = store.lock("rate_index", indexId);
        if (!index.get("is_active").asBoolean()) {
            throw ApiException.unprocessable("index_inactive", "Rate index " + index.get("code").asString() + " is not active");
        }
        if (valueDate == null || rate == null) {
            throw ApiException.unprocessable("invalid_request", "value_date and rate are required");
        }
        if (valueDate.isAfter(store.today())) {
            throw ApiException.unprocessable("value_date_in_future", "A fixing cannot be published for a future date");
        }
        if (rate.abs().compareTo(MAX_ABS_RATE) >= 0) {
            throw ApiException.unprocessable("rate_out_of_range", "rate is an annual fraction between -1 and 1");
        }
        if (store.indexValueExists(indexId, valueDate)) {
            throw ApiException.conflict("index_value_exists", "Index " + index.get("code").asString()
                    + " already has a value for " + valueDate + "; values are append-only");
        }
        ObjectNode v = json.createObjectNode();
        v.put("rate_index_id", indexId.toString());
        v.put("value_date", valueDate.toString());
        v.put("rate", rate);
        if (sourceRef != null) {
            v.put("source_ref", sourceRef);
        }
        JsonNode row = store.insert("rate_index_value", v);
        ObjectNode payload = (ObjectNode) row.deepCopy();
        payload.put("index_code", index.get("code").asString());
        payload.put("currency", index.get("currency").asString().trim());
        outbox.publish("product.rate_index_value.published", "rate_index_value", ProductService.id(row), payload);
        return row.toString();
    }
}
