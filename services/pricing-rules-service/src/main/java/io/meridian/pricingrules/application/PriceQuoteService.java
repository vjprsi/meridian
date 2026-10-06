package io.meridian.pricingrules.application;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.pricingrules.infrastructure.PricingStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Price quotes: issued with a validity window, accepted at most once before expiry (locks the price). */
@Service
public class PriceQuoteService {
    static final String QUOTE = "product_price_quote";
    /** Longest validity a quote may carry. */
    static final Duration MAX_VALIDITY = Duration.ofDays(90);

    private final PricingStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public PriceQuoteService(PricingStore store, CrudEngine crud, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode values = crud.toColumns(QUOTE, body);
        List<ApiException.FieldError> errors = new ArrayList<>();
        BigDecimal rate = dec(values, "interest_rate");
        if (rate != null && (rate.compareTo(new BigDecimal("-1")) <= 0 || rate.compareTo(BigDecimal.ONE) >= 0)) {
            errors.add(new ApiException.FieldError("interest_rate", "out_of_range", "interest_rate is a fraction between -1 and 1"));
        }
        BigDecimal apr = dec(values, "apr");
        if (apr != null && (apr.signum() < 0 || apr.compareTo(BigDecimal.TEN) > 0)) {
            errors.add(new ApiException.FieldError("apr", "out_of_range", "apr must be a non-negative fraction"));
        }
        BigDecimal amount = dec(values, "amount");
        if (amount != null && amount.signum() <= 0) {
            errors.add(new ApiException.FieldError("amount", "must_be_positive", "amount must be positive"));
        }
        BigDecimal total = dec(values, "total_cost");
        if (total != null && total.signum() < 0) {
            errors.add(new ApiException.FieldError("total_cost", "must_not_be_negative", "total_cost cannot be negative"));
        }
        if (values.hasNonNull("term_months")) {
            int term = values.get("term_months").asInt();
            if (term < 1 || term > 600) {
                errors.add(new ApiException.FieldError("term_months", "out_of_range", "term_months must be 1..600"));
            }
        }
        if (values.hasNonNull("currency") && !values.get("currency").asString().matches("[A-Z]{3}")) {
            errors.add(new ApiException.FieldError("currency", "invalid_currency", "currency must be ISO 4217 alpha-3"));
        }
        OffsetDateTime now = io.meridian.common.time.Clocks.now();
        if (values.hasNonNull("expires_at")) {
            OffsetDateTime exp = OffsetDateTime.parse(values.get("expires_at").asString());
            if (!exp.isAfter(now)) {
                errors.add(new ApiException.FieldError("expires_at", "in_past", "expires_at must be in the future"));
            } else if (exp.isAfter(now.plus(MAX_VALIDITY))) {
                errors.add(new ApiException.FieldError("expires_at", "too_far", "A quote may be valid for at most 90 days"));
            }
        }
        if (values.hasNonNull("decision_log_id")
                && store.find(DecisionTableService.LOG, UUID.fromString(values.get("decision_log_id").asString())).isEmpty()) {
            errors.add(new ApiException.FieldError("decision_log_id", "not_found", "decision_log_id does not exist"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_quote", "The quote is invalid", errors);
        }
        String row = crud.insert(QUOTE, values);
        JsonNode node = json.readTree(row);
        outbox.publish("product.product_price_quote.created", QUOTE, UUID.fromString(node.get("id").asString()), node);
        return row;
    }

    @Transactional
    public String accept(UUID id) {
        JsonNode q = store.lock(QUOTE, id);
        if (q.hasNonNull("accepted_at")) {
            throw ApiException.conflict("quote_already_accepted", "Quote " + id + " was accepted at " + q.get("accepted_at").asString());
        }
        OffsetDateTime now = io.meridian.common.time.Clocks.now();
        if (!OffsetDateTime.parse(q.get("expires_at").asString()).isAfter(now)) {
            throw ApiException.conflict("quote_expired", "Quote " + id + " expired at " + q.get("expires_at").asString());
        }
        JsonNode row = store.update(QUOTE, id, json.createObjectNode().put("accepted_at", now.toString()));
        outbox.publish("product.product_price_quote.accepted", QUOTE, id, row);
        return row.toString();
    }

    private static BigDecimal dec(JsonNode n, String f) {
        return n.hasNonNull(f) ? new BigDecimal(n.get(f).asString()) : null;
    }
}
