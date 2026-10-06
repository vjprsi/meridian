package io.meridian.productcatalog.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.productcatalog.api.generated.ProductsApi;
import io.meridian.productcatalog.api.generated.model.Product;
import io.meridian.productcatalog.api.generated.model.ProductCreate;
import io.meridian.productcatalog.api.generated.model.ProductUpdate;
import io.meridian.productcatalog.api.generated.model.ProductVersionPage;
import io.meridian.productcatalog.api.generated.model.ProductsCloneRequest;
import io.meridian.productcatalog.api.generated.model.ProductsFromTemplateRequest;
import io.meridian.productcatalog.api.generated.model.ProductsQuoteRequest;
import io.meridian.productcatalog.api.generated.model.ProductsRetireRequest;
import io.meridian.productcatalog.api.generated.model.ProductsSuspendRequest;
import io.meridian.productcatalog.application.PricingService;
import io.meridian.productcatalog.application.ProductService;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Products: creation (plain, template, clone), guarded edits, suspend/retire, eligibility and quotes. */
@RestController
public class ProductsController implements ProductsApi {
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final Class<Map<String, Object>> MAP = (Class) Map.class;

    private final ProductService products;
    private final PricingService pricing;
    private final CatalogStore store;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public ProductsController(ProductService products, PricingService pricing, CatalogStore store, CrudEngine crud,
                              Idempotency idempotency, JsonMapper json) {
        this.products = products;
        this.pricing = pricing;
        this.store = store;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<Product> productsCreate(String idempotencyKey, ProductCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, Product.class,
                () -> respond(HttpStatus.CREATED, products.create(body)));
    }

    @Override
    public ResponseEntity<Product> productsUpdate(UUID id, String ifMatch, ProductUpdate body) {
        TenantContext.require();
        return respond(HttpStatus.OK, products.update(id, body, ApiSupport.ifMatch(ifMatch)));
    }

    @Override
    public ResponseEntity<Product> productsFromTemplate(String idempotencyKey, ProductsFromTemplateRequest body) {
        JsonNode req = json.valueToTree(body);
        ProductService.FromTemplate r = new ProductService.FromTemplate(UUID.fromString(req.get("template_id").asString()),
                req.get("code").asString(), req.get("name").asString(), req.get("currency").asString(),
                UUID.fromString(req.get("legal_entity_id").asString()));
        return action("from-template", null, idempotencyKey, body, () -> products.fromTemplate(r));
    }

    @Override
    public ResponseEntity<Product> productsClone(UUID id, String idempotencyKey, ProductsCloneRequest body) {
        JsonNode req = json.valueToTree(body);
        return action("clone", id, idempotencyKey, body,
                () -> products.cloneProduct(id, req.get("code").asString(), req.get("name").asString()));
    }

    @Override
    public ResponseEntity<Product> productsSuspend(UUID id, String idempotencyKey, ProductsSuspendRequest body) {
        JsonNode req = json.valueToTree(body);
        return action("suspend", id, idempotencyKey, body, () -> products.suspend(id, ApiSupport.text(req, "reason")));
    }

    @Override
    public ResponseEntity<Product> productsRetire(UUID id, String idempotencyKey, ProductsRetireRequest body) {
        JsonNode req = json.valueToTree(body);
        LocalDate date = req.hasNonNull("retire_date") ? LocalDate.parse(req.get("retire_date").asString()) : null;
        return action("retire", id, idempotencyKey, body, () -> products.retire(id, date, ApiSupport.text(req, "reason")));
    }

    @Override
    public ResponseEntity<ProductVersionPage> productsVersions(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        store.require("product", id);
        return crud.list("product_version", ProductVersionPage.class,
                Crud.params().with("product_id", id).with("limit", limit).with("cursor", cursor));
    }

    /** Eligibility of an applicant profile given as typed query parameters. */
    @Override
    public ResponseEntity<Map<String, Object>> productsEligibilityCheck(UUID id, UUID customerId, Integer age,
            LocalDate dateOfBirth, String residencyCountry, String nationality, Integer kycTier, String segment,
            BigDecimal income, Integer creditScore, String existingProducts, String customerType, String channel,
            LocalDate asOf) {
        TenantContext.require();
        Map<String, Object> profile = new LinkedHashMap<>();
        put(profile, "age", age);
        put(profile, "date_of_birth", dateOfBirth);
        put(profile, "residency_country", residencyCountry);
        put(profile, "nationality", nationality);
        put(profile, "kyc_tier", kycTier);
        put(profile, "segment", segment);
        put(profile, "income", income);
        put(profile, "credit_score", creditScore);
        if (existingProducts != null && !existingProducts.isBlank()) {
            profile.put("existing_products", Arrays.stream(existingProducts.split(","))
                    .map(String::trim).filter(x -> !x.isEmpty()).toList());
        }
        put(profile, "customer_type", customerType);
        put(profile, "as_of", asOf);
        Map<String, Object> result = pricing.eligibility(id, customerId, profile, channel);
        return ResponseEntity.ok(json.convertValue(store.toNode(result), MAP));
    }

    /**
     * Quote for amount/term/channel; the optional {@code applicant} object (segment, relationship_balance,
     * products_held, new_customer, salary_credit, promotion_code ...) drives promotions, relationship pricing and
     * fee waivers.
     */
    @Override
    public ResponseEntity<Map<String, Object>> productsQuote(UUID id, String idempotencyKey, ProductsQuoteRequest body) {
        TenantContext.require();
        PricingService.QuoteRequest q = new PricingService.QuoteRequest(body.getCustomerId(), body.getAmount(),
                body.getTermMonths(), body.getChannel() == null ? null : body.getChannel().getValue(),
                body.getApplicant() == null ? Map.of() : body.getApplicant());
        return ApiSupport.result(idempotency, json, idempotencyKey, ApiSupport.idem(json, "quote", id, body),
                () -> store.toNode(pricing.quote(id, q)));
    }

    private static void put(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value instanceof LocalDate d ? d.toString() : value);
        }
    }

    private ResponseEntity<Product> action(String op, UUID id, String key, Object body, Supplier<String> run) {
        TenantContext.require();
        return idempotency.execute(key, ApiSupport.idem(json, op, id, body), Product.class,
                () -> respond(HttpStatus.OK, run.get()));
    }

    private ResponseEntity<Product> respond(HttpStatus status, String row) {
        return ApiSupport.withEtag(status, crud.read(row, Product.class), json.readTree(row));
    }
}
