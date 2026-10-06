package io.meridian.referencedata.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.events.OutboxWriter;
import io.meridian.referencedata.domain.FxMath;
import io.meridian.referencedata.infrastructure.RefDataStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * FX rates (tenant-scoped, append-only observations): publication with validation, bulk loads, latest-rate
 * lookup and conversion (direct, inverse or cross via the pivot currency) with HALF_EVEN rounding.
 */
@Service
public class FxService {
    public static final Set<String> RATE_TYPES = Set.of("MID", "BUY", "SELL", "CASH_BUY", "CASH_SELL", "REVALUATION");
    private static final Pattern CCY = Pattern.compile("^[A-Z]{3}$");
    static final int MAX_BULK = 1000;

    private final CrudEngine crud;
    private final RefDataStore store;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final String pivot;

    public FxService(CrudEngine crud, RefDataStore store, OutboxWriter outbox, JsonMapper json,
                     @Value("${meridian.fx.pivot-currency:USD}") String pivot) {
        this.crud = crud;
        this.store = store;
        this.outbox = outbox;
        this.json = json;
        this.pivot = pivot;
    }

    /** One leg of a conversion: units of {@code quote} per 1 {@code base}. */
    record Leg(String base, String quote, BigDecimal rate, String fxRateId, boolean derived, JsonNode row) {}

    // ------------------------------------------------------------------ publish

    @Transactional
    public String create(Object body) {
        ObjectNode v = crud.toColumns("fx_rate", body);
        List<FieldError> errors = validate(v, "");
        if (!errors.isEmpty()) {
            throw new ApiException(422, errors.getFirst().code(), errors.getFirst().message(), errors);
        }
        return publish(v);
    }

    /**
     * Loads a batch of rates all-or-nothing. {@code defaults} (fx_rate_source_id, rate_type, as_of_date, valid_from,
     * valid_to) apply to every rate that does not set the field itself.
     */
    @Transactional
    public JsonNode bulk(JsonNode rates, JsonNode defaults) {
        if (rates == null || !rates.isArray() || rates.isEmpty()) {
            throw ApiException.unprocessable("invalid_rates", "rates must be a non-empty array");
        }
        if (rates.size() > MAX_BULK) {
            throw ApiException.unprocessable("too_many_rates", "At most " + MAX_BULK + " rates per bulk load");
        }
        List<ObjectNode> rows = new ArrayList<>();
        List<FieldError> errors = new ArrayList<>();
        for (int i = 0; i < rates.size(); i++) {
            JsonNode item = rates.get(i);
            String prefix = "rates[" + i + "].";
            if (!item.isObject()) {
                errors.add(new FieldError("rates[" + i + "]", "invalid_rate", "each rate must be an object"));
                continue;
            }
            ObjectNode v = json.createObjectNode();
            if (defaults != null) {
                defaults.propertyNames().forEach(name -> v.set(name, defaults.get(name)));
            }
            item.propertyNames().forEach(name -> v.set(name, item.get(name)));
            errors.addAll(validate(v, prefix));
            rows.add(v);
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_rates",
                    errors.size() + " rate(s) failed validation; nothing was loaded", errors);
        }
        ArrayNode loaded = json.createArrayNode();
        for (ObjectNode v : rows) {
            loaded.add(json.readTree(publish(v)));
        }
        ObjectNode out = json.createObjectNode();
        out.put("loaded", loaded.size());
        out.set("rates", loaded);
        return out;
    }

    private String publish(ObjectNode v) {
        v.remove("id");
        String row = crud.insert("fx_rate", v);
        JsonNode r = json.readTree(row);
        outbox.publish("platform.fx_rate.published", "fx_rate", UUID.fromString(r.get("id").asString()), r);
        return row;
    }

    /**
     * Validates and completes one rate: inverse_rate is always computed as 1 / rate (read-only in the contract; a
     * client value is ignored); rate_type defaults to MID, valid_from to now and as_of_date to valid_from's date.
     */
    List<FieldError> validate(ObjectNode v, String p) {
        List<FieldError> errors = new ArrayList<>();
        String base = text(v, "base_currency");
        String quote = text(v, "quote_currency");
        for (String[] c : new String[][] {{"base_currency", base}, {"quote_currency", quote}}) {
            if (c[1] == null || !CCY.matcher(c[1]).matches()) {
                errors.add(new FieldError(p + c[0], "invalid_currency", c[0] + " must be an ISO 4217 code"));
            } else if (store.currency(c[1]).filter(n -> n.path("is_active").asBoolean(true)).isEmpty()) {
                errors.add(new FieldError(p + c[0], "unknown_currency", "Currency " + c[1] + " is not defined or inactive"));
            }
        }
        if (base != null && base.equals(quote)) {
            errors.add(new FieldError(p + "quote_currency", "same_currency", "base and quote currency must differ"));
        }
        String type = text(v, "rate_type");
        if (type == null) {
            v.put("rate_type", "MID");
        } else if (!RATE_TYPES.contains(type)) {
            errors.add(new FieldError(p + "rate_type", "invalid_rate_type", "rate_type must be one of " + RATE_TYPES));
        }
        String sourceId = text(v, "fx_rate_source_id");
        try {
            Optional<JsonNode> source = sourceId == null ? Optional.empty() : store.byId("fx_rate_source", UUID.fromString(sourceId));
            if (source.isEmpty()) {
                errors.add(new FieldError(p + "fx_rate_source_id", "unknown_source", "fx_rate_source_id must reference an FX rate source"));
            } else if (!source.get().path("is_active").asBoolean()) {
                errors.add(new FieldError(p + "fx_rate_source_id", "source_inactive", "FX rate source " + sourceId + " is inactive"));
            }
        } catch (IllegalArgumentException e) {
            errors.add(new FieldError(p + "fx_rate_source_id", "invalid_uuid", "fx_rate_source_id must be a UUID"));
        }
        v.remove("inverse_rate");
        BigDecimal rate = decimal(v, "rate", p, errors);
        if (rate != null) {
            if (rate.signum() <= 0) {
                errors.add(new FieldError(p + "rate", "invalid_rate", "rate must be positive"));
            } else {
                v.put("inverse_rate", FxMath.inverse(rate).toPlainString());
            }
        } else if (!v.hasNonNull("rate")) {
            errors.add(new FieldError(p + "rate", "required", "rate is required"));
        }
        OffsetDateTime from = timestamp(v, "valid_from", p, errors);
        if (from == null && !v.hasNonNull("valid_from")) {
            from = OffsetDateTime.now(ZoneOffset.UTC);
            v.put("valid_from", from.toString());
        }
        OffsetDateTime to = timestamp(v, "valid_to", p, errors);
        if (from != null && to != null && !to.isAfter(from)) {
            errors.add(new FieldError(p + "valid_to", "invalid_validity", "valid_to must be after valid_from"));
        }
        if (!v.hasNonNull("as_of_date") && from != null) {
            v.put("as_of_date", from.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().toString());
        } else if (v.hasNonNull("as_of_date")) {
            try {
                LocalDate.parse(v.get("as_of_date").asString());
            } catch (DateTimeParseException e) {
                errors.add(new FieldError(p + "as_of_date", "invalid_date", "as_of_date must be YYYY-MM-DD"));
            }
        }
        return errors;
    }

    // ------------------------------------------------------------------ query

    @Transactional(readOnly = true)
    public JsonNode latest(String base, String quote, String type) {
        String t = rateType(type);
        requireCcy("base", base);
        requireCcy("quote", quote);
        Leg leg = leg(base, quote, t, OffsetDateTime.now(ZoneOffset.UTC))
                .orElseThrow(() -> new ApiException(404, "rate_not_found",
                        "No " + t + " rate for " + base + "/" + quote + " is currently valid"));
        if (!leg.derived()) {
            return leg.row();
        }
        ObjectNode derived = ((ObjectNode) leg.row()).deepCopy();
        derived.put("base_currency", base);
        derived.put("quote_currency", quote);
        derived.put("rate", leg.row().get("inverse_rate").asString());
        derived.put("inverse_rate", leg.row().get("rate").asString());
        return derived;
    }

    @Transactional(readOnly = true)
    public JsonNode convert(BigDecimal amount, String from, String to, String rateType) {
        if (amount.signum() < 0) {
            throw ApiException.unprocessable("invalid_amount", "amount must not be negative");
        }
        String type = rateType(rateType);
        int minor = minorUnits(to);
        for (String c : List.of(from, to)) {
            if (store.currency(c).isEmpty()) {
                throw ApiException.unprocessable("unknown_currency", "Currency " + c + " is not defined");
            }
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<Leg> legs = new ArrayList<>();
        String path;
        BigDecimal rate;
        if (from.equals(to)) {
            path = "IDENTITY";
            rate = BigDecimal.ONE;
        } else {
            Optional<Leg> direct = leg(from, to, type, now);
            if (direct.isPresent()) {
                legs.add(direct.get());
                path = direct.get().derived() ? "INVERSE" : "DIRECT";
                rate = direct.get().rate();
            } else if (!from.equals(pivot) && !to.equals(pivot)) {
                Leg first = leg(from, pivot, type, now).orElseThrow(() -> noRate(from, to, type));
                Leg second = leg(pivot, to, type, now).orElseThrow(() -> noRate(from, to, type));
                legs.add(first);
                legs.add(second);
                path = "CROSS";
                rate = FxMath.cross(first.rate(), second.rate());
            } else {
                throw noRate(from, to, type);
            }
        }
        ObjectNode out = json.createObjectNode();
        out.put("amount", amount.toPlainString());
        out.put("from_currency", from);
        out.put("to_currency", to);
        out.put("rate_type", type);
        out.put("rate", FxMath.display(rate).toPlainString());
        out.put("converted_amount", FxMath.convert(amount, rate, minor).toPlainString());
        out.put("rounding", "HALF_EVEN");
        out.put("minor_units", minor);
        out.put("path", path);
        if ("CROSS".equals(path)) {
            out.put("pivot_currency", pivot);
        }
        ArrayNode arr = out.putArray("legs");
        for (Leg l : legs) {
            ObjectNode n = arr.addObject();
            n.put("base_currency", l.base());
            n.put("quote_currency", l.quote());
            n.put("rate", FxMath.display(l.rate()).toPlainString());
            n.put("fx_rate_id", l.fxRateId());
            n.put("derived_from_inverse", l.derived());
        }
        out.put("as_of", now.toString());
        return out;
    }

    private Optional<Leg> leg(String base, String quote, String type, OffsetDateTime at) {
        Optional<JsonNode> direct = store.latestRate(base, quote, type, at);
        if (direct.isPresent()) {
            JsonNode r = direct.get();
            return Optional.of(new Leg(base, quote, new BigDecimal(r.get("rate").asString()), r.get("id").asString(), false, r));
        }
        return store.latestRate(quote, base, type, at).map(r -> new Leg(base, quote,
                new BigDecimal(r.get("inverse_rate").asString()), r.get("id").asString(), true, r));
    }

    private int minorUnits(String code) {
        return store.currency(code).map(c -> c.path("minor_units").asInt(2)).orElse(2);
    }

    private static ApiException noRate(String from, String to, String type) {
        return new ApiException(404, "rate_not_found",
                "No " + type + " rate path from " + from + " to " + to + " (direct, inverse or cross)");
    }

    private static String rateType(String type) {
        String t = type == null || type.isBlank() ? "MID" : type.trim();
        if (!RATE_TYPES.contains(t)) {
            throw ApiException.badRequest("invalid_rate_type", "type must be one of " + RATE_TYPES);
        }
        return t;
    }

    private static void requireCcy(String param, String v) {
        if (v == null || !CCY.matcher(v).matches()) {
            throw ApiException.badRequest("invalid_currency", param + " must be an ISO 4217 code (query parameter)");
        }
    }

    private static String text(JsonNode v, String f) {
        return v.hasNonNull(f) ? v.get(f).asString() : null;
    }

    private static BigDecimal decimal(JsonNode v, String f, String p, List<FieldError> errors) {
        if (!v.hasNonNull(f)) {
            return null;
        }
        try {
            return new BigDecimal(v.get(f).asString());
        } catch (NumberFormatException e) {
            errors.add(new FieldError(p + f, "invalid_decimal", f + " must be a decimal string"));
            return null;
        }
    }

    private static OffsetDateTime timestamp(JsonNode v, String f, String p, List<FieldError> errors) {
        if (!v.hasNonNull(f)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(v.get(f).asString());
        } catch (DateTimeParseException e) {
            errors.add(new FieldError(p + f, "invalid_timestamp", f + " must be an RFC 3339 timestamp"));
            return null;
        }
    }
}
