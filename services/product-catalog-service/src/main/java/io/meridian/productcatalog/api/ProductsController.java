package io.meridian.productcatalog.api;

import io.meridian.common.error.ApiException;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Products: creation (plain, template, clone), guarded edits, suspend/retire, eligibility and quotes. */
@RestController
public class ProductsController implements ProductsApi {
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

    /**
     * {@code GET /v1/products/{id}/eligibility-check?customer_id=&age=|date_of_birth=&residency_country=&nationality=
     * &kyc_tier=&segment=&income=&credit_score=&existing_products=A,B&customer_type=&channel=&as_of=}
     */
    @Override
    public ResponseEntity<Void> productsEligibilityCheck(UUID id) {
        TenantContext.require();
        Map<String, Object> profile = new LinkedHashMap<>(ApiSupport.queryParams());
        String customer = (String) profile.remove("customer_id");
        String channel = (String) profile.remove("channel");
        UUID customerId;
        try {
            customerId = customer == null ? null : UUID.fromString(customer);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_customer_id", "customer_id must be a UUID");
        }
        Map<String, Object> result = pricing.eligibility(id, customerId, profile, channel);
        return ApiSupport.document(MediaType.APPLICATION_JSON, store.toNode(result).toString());
    }

    /**
     * Quote for amount/term/channel; an optional {@code applicant} object in the body (segment,
     * relationship_balance, products_held, new_customer, salary_credit, promotion_code ...) drives promotions,
     * relationship pricing and fee waivers.
     */
    @Override
    public ResponseEntity<Void> productsQuote(UUID id, String idempotencyKey, ProductsQuoteRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        Map<String, Object> extras = ApiSupport.rawBodyExtras(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> applicant = extras.get("applicant") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        PricingService.QuoteRequest q = new PricingService.QuoteRequest(
                req.hasNonNull("customer_id") ? UUID.fromString(req.get("customer_id").asString()) : null,
                req.hasNonNull("amount") ? new BigDecimal(req.get("amount").asString()) : null,
                req.hasNonNull("term_months") ? req.get("term_months").asInt() : null,
                ApiSupport.text(req, "channel"), applicant);
        return ApiSupport.asVoid(idempotency.execute(idempotencyKey, ApiSupport.idem(json, "quote", id, body), String.class,
                () -> ResponseEntity.ok(store.toNode(pricing.quote(id, q)).toString())));
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
