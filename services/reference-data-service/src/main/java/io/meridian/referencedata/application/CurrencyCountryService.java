package io.meridian.referencedata.application;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.referencedata.infrastructure.RefDataStore;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Currencies (ISO 4217) and countries (ISO 3166): validation and change events. Platform-global tables. */
@Service
public class CurrencyCountryService {
    private static final Pattern NUMERIC3 = Pattern.compile("^[0-9]{3}$");
    private static final Pattern ALPHA3 = Pattern.compile("^[A-Z]{3}$");

    private final CrudEngine crud;
    private final RefDataStore store;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public CurrencyCountryService(CrudEngine crud, RefDataStore store, OutboxWriter outbox, JsonMapper json) {
        this.crud = crud;
        this.store = store;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String createCurrency(Object body) {
        ObjectNode v = crud.toColumns("currency", body);
        if (!v.has("minor_units")) {
            v.put("minor_units", 2);
        }
        validateCurrency(v);
        if (store.currency(v.get("code").asString()).isPresent()) {
            throw ApiException.conflict("duplicate", "Currency " + v.get("code").asString() + " already exists");
        }
        String row = crud.insert("currency", v);
        JsonNode r = json.readTree(row);
        outbox.publish("platform.currency.changed", "currency", UUID.fromString(r.get("id").asString()), r);
        return row;
    }

    @Transactional
    public <T> ResponseEntity<T> updateCurrency(UUID id, String ifMatch, Object body, Class<T> type) {
        JsonNode before = store.byId("currency", id).orElseThrow(() -> ApiException.notFound("currency", id));
        ObjectNode v = crud.toColumns("currency", body);
        rejectCodeChange(before, v, "code");
        ObjectNode merged = (ObjectNode) before.deepCopy();
        merged.setAll(v);
        validateCurrency(merged);
        ResponseEntity<T> r = crud.update("currency", type, body, ifMatch, Crud.params().with("id", id));
        outbox.publish("platform.currency.changed", "currency", id, json.valueToTree(r.getBody()));
        return r;
    }

    @Transactional
    public <T> ResponseEntity<T> updateCountry(UUID id, String ifMatch, Object body, Class<T> type) {
        JsonNode before = store.byId("country", id).orElseThrow(() -> ApiException.notFound("country", id));
        ObjectNode v = crud.toColumns("country", body);
        rejectCodeChange(before, v, "code");
        if (v.hasNonNull("alpha3") && !ALPHA3.matcher(v.get("alpha3").asString()).matches()) {
            throw ApiException.unprocessable("invalid_alpha3", "alpha3 must be three uppercase letters");
        }
        if (v.hasNonNull("numeric_code") && !NUMERIC3.matcher(v.get("numeric_code").asString()).matches()) {
            throw ApiException.unprocessable("invalid_numeric_code", "numeric_code must be three digits");
        }
        if (v.hasNonNull("iban_length") && (v.get("iban_length").asInt() < 15 || v.get("iban_length").asInt() > 34)) {
            throw ApiException.unprocessable("invalid_iban_length", "iban_length must be between 15 and 34");
        }
        if (v.hasNonNull("postal_code_regex")) {
            try {
                Pattern.compile(v.get("postal_code_regex").asString());
            } catch (PatternSyntaxException e) {
                throw ApiException.unprocessable("invalid_postal_code_regex", "postal_code_regex is not a valid pattern");
            }
        }
        if (v.hasNonNull("currency_code") && store.currency(v.get("currency_code").asString()).isEmpty()) {
            throw ApiException.unprocessable("unknown_currency", "Currency " + v.get("currency_code").asString() + " is not defined");
        }
        ResponseEntity<T> r = crud.update("country", type, body, ifMatch, Crud.params().with("id", id));
        JsonNode after = json.valueToTree(r.getBody());
        boolean sanctionsChanged = before.path("is_sanctioned").asBoolean() != after.path("is_sanctioned").asBoolean();
        boolean fatfChanged = !Objects.equals(before.path("fatf_status").asString(), after.path("fatf_status").asString());
        if (sanctionsChanged || fatfChanged) {
            ObjectNode payload = ((ObjectNode) after).deepCopy();
            ObjectNode previous = payload.putObject("previous");
            previous.put("is_sanctioned", before.path("is_sanctioned").asBoolean());
            previous.put("fatf_status", before.path("fatf_status").asString());
            outbox.publish("platform.country.risk_changed", "country", id, payload);
        }
        return r;
    }

    private static void rejectCodeChange(JsonNode before, ObjectNode v, String field) {
        if (v.hasNonNull(field) && !v.get(field).asString().equals(before.path(field).asString().trim())) {
            throw ApiException.unprocessable("immutable_field", field + " cannot be changed; create a new record instead");
        }
    }

    static void validateCurrency(JsonNode v) {
        int minor = v.path("minor_units").asInt(2);
        if (minor < 0 || minor > 8) {
            throw ApiException.unprocessable("invalid_minor_units", "minor_units must be between 0 and 8");
        }
        if (v.hasNonNull("numeric_code") && !NUMERIC3.matcher(v.get("numeric_code").asString()).matches()) {
            throw ApiException.unprocessable("invalid_numeric_code", "numeric_code must be three digits (ISO 4217)");
        }
        if (v.hasNonNull("cash_rounding_increment")) {
            BigDecimal inc = new BigDecimal(v.get("cash_rounding_increment").asString());
            if (inc.signum() <= 0) {
                throw ApiException.unprocessable("invalid_cash_rounding_increment", "cash_rounding_increment must be positive");
            }
            if (inc.stripTrailingZeros().scale() > minor) {
                throw ApiException.unprocessable("invalid_cash_rounding_increment",
                        "cash_rounding_increment cannot have more decimals than minor_units (" + minor + ")");
            }
        }
    }
}
