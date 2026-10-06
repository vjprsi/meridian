package io.meridian.loanorigination;

import io.meridian.common.error.ApiException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stateful stand-ins for product-catalog-service and loan-service behind the mocked ServiceClient. The catalog
 * serves one sellable lending product (and one with a draft version); loan-service books loans idempotently per
 * Idempotency-Key and can be told to fail the next bookings. Every call is recorded for assertions.
 */
public class FakeServices {
    public record Call(String service, String method, String path, JsonNode body, String key) {}

    static final String PRODUCT = "0199b000-0000-7000-8000-00000000f001";
    static final String VERSION = "0199b000-0000-7000-8000-00000000f002";
    static final String DRAFT_PRODUCT = "0199b000-0000-7000-8000-00000000f003";
    static final String DRAFT_VERSION = "0199b000-0000-7000-8000-00000000f004";
    static final String LEGAL_ENTITY = "0199b000-0000-7000-8000-00000000e001";

    private final JsonMapper json;
    private final Map<String, ObjectNode> loansByKey = new HashMap<>();
    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    public final AtomicInteger failNextBookings = new AtomicInteger();

    public FakeServices(JsonMapper json) {
        this.json = json;
    }

    public synchronized JsonNode get(String service, String path) {
        calls.add(new Call(service, "GET", path, null, null));
        if (!"product-catalog-service".equals(service)) {
            throw new IllegalArgumentException("unexpected GET " + service + " " + path);
        }
        String p = path.split("\\?")[0];
        return switch (p) {
            case "/v1/products/" + PRODUCT -> json.readTree("""
                    {"id":"%s","code":"PL-EUR","currency":"EUR","family":"INSTALMENT_LOAN","legal_entity_id":"%s",
                     "current_version_id":"%s","status":"ACTIVE","is_sellable":true}""".formatted(PRODUCT, LEGAL_ENTITY, VERSION));
            case "/v1/products/" + DRAFT_PRODUCT -> json.readTree("""
                    {"id":"%s","code":"PL-NEW","currency":"EUR","family":"INSTALMENT_LOAN","legal_entity_id":"%s",
                     "current_version_id":"%s","status":"ACTIVE","is_sellable":true}""".formatted(DRAFT_PRODUCT, LEGAL_ENTITY, DRAFT_VERSION));
            case "/v1/product-versions/" + VERSION -> json.readTree("{\"id\":\"" + VERSION + "\",\"product_id\":\"" + PRODUCT
                    + "\",\"status\":\"PUBLISHED\"}");
            case "/v1/product-versions/" + DRAFT_VERSION -> json.readTree("{\"id\":\"" + DRAFT_VERSION + "\",\"product_id\":\""
                    + DRAFT_PRODUCT + "\",\"status\":\"DRAFT\"}");
            case "/v1/product-versions/" + VERSION + "/interest-rates" -> json.readTree("""
                    {"data":[{"rate_kind":"DEBIT_INTEREST","rate_type":"FIXED","fixed_rate":"0.10","day_count":"ACT_365","currency":"EUR"},
                             {"rate_kind":"PENALTY_INTEREST","rate_type":"FIXED","fixed_rate":"0.05"}],"has_more":false}""");
            case "/v1/product-versions/" + VERSION + "/fees" -> json.readTree("""
                    {"data":[{"code":"ORIG","trigger":"LOAN_ORIGINATION","calculation_method":"FLAT_PLUS_PERCENTAGE",
                              "flat_amount":"50.00","percentage":"0.01","max_amount":"300.00","currency":"EUR"}],"has_more":false}""");
            case "/v1/product-versions/" + VERSION + "/parameters" -> json.readTree("""
                    {"data":[{"param_key":"lending.min_amount","value_text":"1000"},
                             {"param_key":"lending.max_amount","value_text":"50000"},
                             {"param_key":"lending.min_term_months","value_text":"6"},
                             {"param_key":"lending.max_term_months","value_text":"60"},
                             {"param_key":"lending.offer_validity_days","value_text":"30"}],"has_more":false}""");
            default -> throw new ApiException(404, "not_found", path);
        };
    }

    public synchronized JsonNode post(String service, String path, Object body, String key) {
        JsonNode b = body == null ? json.createObjectNode() : json.valueToTree(body);
        calls.add(new Call(service, "POST", path, b, key));
        if ("loan-service".equals(service) && "/v1/loans".equals(path)) {
            if (key == null || key.isBlank()) {
                throw ApiException.badRequest("idempotency_key_required", "key");
            }
            if (loansByKey.containsKey(key)) {
                return loansByKey.get(key);
            }
            if (failNextBookings.get() > 0) {
                failNextBookings.decrementAndGet();
                throw new ApiException(502, "upstream_error", "loan-service unavailable");
            }
            ObjectNode loan = ((ObjectNode) b).deepCopy();
            loan.put("id", UUID.randomUUID().toString());
            loan.put("loan_number", "LN-" + (loansByKey.size() + 1));
            loan.put("status", "PENDING_DISBURSEMENT");
            loansByKey.put(key, loan);
            return loan;
        }
        throw new IllegalArgumentException("unexpected POST " + service + " " + path);
    }

    public List<Call> bookings() {
        synchronized (calls) {
            return calls.stream().filter(c -> "/v1/loans".equals(c.path()) && "POST".equals(c.method())).toList();
        }
    }
}
