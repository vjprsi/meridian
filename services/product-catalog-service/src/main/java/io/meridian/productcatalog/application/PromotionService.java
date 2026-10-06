package io.meridian.productcatalog.application;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.productcatalog.domain.Lifecycles;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Promotional rates: validated campaigns whose status follows their window; cancellation ends them early. */
@Service
public class PromotionService {
    static final String PROMO = "promotional_rate";

    private final CatalogStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public PromotionService(CatalogStore store, CrudEngine crud, OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode values = crud.toColumns(PROMO, body);
        UUID productId = UUID.fromString(values.get("product_id").asString());
        JsonNode product = store.find("product", productId).orElseThrow(() ->
                ApiException.unprocessable("product_not_found", "product " + productId + " does not exist"));
        if ("RETIRED".equals(product.get("status").asString())) {
            throw ApiException.conflict("product_retired", "Cannot promote a retired product");
        }
        validate(values);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime starts = OffsetDateTime.parse(values.get("starts_at").asString());
        OffsetDateTime ends = OffsetDateTime.parse(values.get("ends_at").asString());
        if (!ends.isAfter(now)) {
            throw ApiException.unprocessable("promotion_in_past", "ends_at must be in the future");
        }
        String status = starts.isAfter(now) ? "SCHEDULED" : Lifecycles.PROMOTION.apply("SCHEDULED", "start");
        if (values.hasNonNull("status") && !values.get("status").asString().equals(status)) {
            throw ApiException.unprocessable("invalid_initial_status", "Promotion status follows its window; it would be " + status);
        }
        values.put("status", status);
        JsonNode row = store.insert(PROMO, values);
        if ("ACTIVE".equals(status)) {
            outbox.publish("product.promotional_rate.started", PROMO, ProductService.id(row), row);
        }
        return row.toString();
    }

    @Transactional
    public String update(UUID id, Object body, Integer expectedVersion) {
        JsonNode current = store.lock(PROMO, id);
        String status = current.get("status").asString();
        if ("CANCELLED".equals(status) || "EXPIRED".equals(status)) {
            throw ApiException.conflict("promotion_closed", "Promotion is " + status + " and can no longer change");
        }
        ObjectNode values = crud.toColumns(PROMO, body);
        if (values.hasNonNull("status") && !values.get("status").asString().equals(status)) {
            throw ApiException.conflict("invalid_transition", "Promotion status is managed by its window and the cancel action");
        }
        for (String f : List.of("product_id", "code")) {
            if (values.hasNonNull(f) && !values.get(f).asString().equals(current.get(f).asString())) {
                throw ApiException.unprocessable("immutable_field", f + " cannot be changed");
            }
        }
        if ("ACTIVE".equals(status) && values.hasNonNull("starts_at")
                && !OffsetDateTime.parse(values.get("starts_at").asString()).isEqual(OffsetDateTime.parse(current.get("starts_at").asString()))) {
            throw ApiException.conflict("immutable_field", "starts_at of a running promotion cannot change");
        }
        ObjectNode merged = (ObjectNode) current.deepCopy();
        merged.setAll(values);
        validate(merged);
        return crud.updateRow(PROMO, Map.of("id", id), values, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match does not match the current version; reload and retry"));
    }

    @Transactional
    public String cancel(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "A cancellation reason is required");
        }
        JsonNode p = store.lock(PROMO, id);
        String to = Lifecycles.PROMOTION.apply(p.get("status").asString(), "cancel");
        ObjectNode values = json.createObjectNode();
        values.put("status", to);
        OffsetDateTime now = OffsetDateTime.now();
        if (OffsetDateTime.parse(p.get("ends_at").asString()).isAfter(now)) {
            values.put("ends_at", now.toString());
        }
        JsonNode row = store.update(PROMO, id, values);
        ObjectNode payload = (ObjectNode) row.deepCopy();
        payload.put("reason", reason);
        payload.put("previous_status", p.get("status").asString());
        outbox.publish("product.promotional_rate.ended", PROMO, id, payload);
        return row.toString();
    }

    private static void validate(JsonNode v) {
        List<ApiException.FieldError> e = new ArrayList<>();
        OffsetDateTime starts = OffsetDateTime.parse(v.get("starts_at").asString());
        OffsetDateTime ends = OffsetDateTime.parse(v.get("ends_at").asString());
        if (!ends.isAfter(starts)) {
            e.add(new ApiException.FieldError("ends_at", "invalid_window", "ends_at must be after starts_at"));
        }
        boolean bonus = v.hasNonNull("bonus_rate");
        boolean override = v.hasNonNull("override_rate");
        boolean feeDiscount = v.hasNonNull("fee_discount_percentage");
        if (!bonus && !override && !feeDiscount) {
            e.add(new ApiException.FieldError("bonus_rate", "required",
                    "A promotion needs bonus_rate, override_rate or fee_discount_percentage"));
        }
        if (bonus && override) {
            e.add(new ApiException.FieldError("override_rate", "conflicting_fields", "Use either bonus_rate or override_rate"));
        }
        if ((bonus || override) && !v.hasNonNull("rate_kind")) {
            e.add(new ApiException.FieldError("rate_kind", "required", "Rate promotions need rate_kind"));
        }
        for (String f : List.of("bonus_rate", "override_rate")) {
            if (v.hasNonNull(f) && new BigDecimal(v.get(f).asString()).abs().compareTo(BigDecimal.ONE) >= 0) {
                e.add(new ApiException.FieldError(f, "out_of_range", f + " is an annual fraction"));
            }
        }
        if (feeDiscount) {
            BigDecimal d = new BigDecimal(v.get("fee_discount_percentage").asString());
            if (d.signum() <= 0 || d.compareTo(BigDecimal.ONE) > 0) {
                e.add(new ApiException.FieldError("fee_discount_percentage", "out_of_range", "fee_discount_percentage is in (0, 1]"));
            }
        }
        if (v.hasNonNull("applies_for_days") && v.get("applies_for_days").asInt() < 1) {
            e.add(new ApiException.FieldError("applies_for_days", "out_of_range", "applies_for_days must be positive"));
        }
        if (v.hasNonNull("max_balance") && new BigDecimal(v.get("max_balance").asString()).signum() <= 0) {
            e.add(new ApiException.FieldError("max_balance", "must_be_positive", "max_balance must be positive"));
        }
        if (!e.isEmpty()) {
            throw new ApiException(422, "invalid_promotion", "The promotion is invalid", e);
        }
    }
}
