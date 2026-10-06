package io.meridian.customer.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.customer.api.generated.CustomersApi;
import io.meridian.customer.api.generated.model.Customer;
import io.meridian.customer.api.generated.model.CustomerCreate;
import io.meridian.customer.api.generated.model.CustomerKycTierHistoryPage;
import io.meridian.customer.api.generated.model.CustomerPage;
import io.meridian.customer.api.generated.model.CustomerRelationshipPage;
import io.meridian.customer.api.generated.model.CustomerUpdate;
import io.meridian.customer.api.generated.model.CustomersChangeTierRequest;
import io.meridian.customer.api.generated.model.CustomersCloseRequest;
import io.meridian.customer.api.generated.model.CustomersDuplicatesCheckRequest;
import io.meridian.customer.api.generated.model.CustomersMarkDeceasedRequest;
import io.meridian.customer.api.generated.model.CustomersMergeRequest;
import io.meridian.customer.api.generated.model.CustomersReactivateRequest;
import io.meridian.customer.api.generated.model.CustomersSuspendRequest;
import io.meridian.customer.application.CustomerQueryService;
import io.meridian.customer.application.CustomerService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Customers: master data, lifecycle actions, KYC tier (maker-checker), merge, search, duplicates and the
 * single customer view. Contract: generated/openapi/services/customer-service.openapi.yaml.
 * <p>
 * Contract gaps handled here: {@code GET /search} declares no {@code q} parameter (read from the query string);
 * {@code /view} and {@code /ownership-tree} are declared 204 without a body but return 200 with the document;
 * duplicates-check items carry the additive {@code match_score} and {@code match_reasons} fields.
 */
@RestController
public class CustomersController implements CustomersApi {
    private final CustomerService customers;
    private final CustomerQueryService queries;
    private final Http http;
    private final HttpServletRequest request;

    public CustomersController(CustomerService customers, CustomerQueryService queries, Http http, HttpServletRequest request) {
        this.customers = customers;
        this.queries = queries;
        this.http = http;
        this.request = request;
    }

    @Override
    public ResponseEntity<Customer> customersCreate(String idempotencyKey, CustomerCreate body) {
        return http.created(idempotencyKey, "customers.create", null, body, Customer.class, () -> customers.create(body));
    }

    @Override
    public ResponseEntity<Customer> customersUpdate(UUID id, String ifMatch, CustomerUpdate body) {
        TenantContext.require();
        var row = customers.update(id, ifMatch, body);
        return ResponseEntity.ok().eTag("\"" + row.get("row_version").asString() + "\"").body(http.read(row, Customer.class));
    }

    @Override
    public ResponseEntity<Customer> customersActivate(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "customers.activate", id, null, Customer.class, () -> customers.activate(id));
    }

    @Override
    public ResponseEntity<Customer> customersSuspend(UUID id, String idempotencyKey, CustomersSuspendRequest body) {
        String reason = http.tree(body).get("reason").asString();
        return http.ok(idempotencyKey, "customers.suspend", id, body, Customer.class, () -> customers.suspend(id, reason));
    }

    @Override
    public ResponseEntity<Customer> customersReactivate(UUID id, String idempotencyKey, CustomersReactivateRequest body) {
        String reason = http.tree(body).get("reason").asString();
        return http.ok(idempotencyKey, "customers.reactivate", id, body, Customer.class, () -> customers.reactivate(id, reason));
    }

    @Override
    public ResponseEntity<Customer> customersClose(UUID id, String idempotencyKey, CustomersCloseRequest body) {
        String reason = http.tree(body).get("reason").asString();
        return http.ok(idempotencyKey, "customers.close", id, body, Customer.class, () -> customers.close(id, reason));
    }

    @Override
    public ResponseEntity<Customer> customersMarkDeceased(UUID id, String idempotencyKey, CustomersMarkDeceasedRequest body) {
        JsonNode req = http.tree(body);
        LocalDate on = LocalDate.parse(req.get("deceased_on").asString());
        UUID document = UUID.fromString(req.get("document_id").asString());
        return http.ok(idempotencyKey, "customers.mark-deceased", id, body, Customer.class,
                () -> customers.markDeceased(id, on, document));
    }

    @Override
    public ResponseEntity<Customer> customersMerge(UUID id, String idempotencyKey, CustomersMergeRequest body) {
        JsonNode req = http.tree(body);
        UUID duplicate = UUID.fromString(req.get("duplicate_customer_id").asString());
        String reason = req.get("reason").asString();
        return http.ok(idempotencyKey, "customers.merge", id, body, Customer.class, () -> customers.merge(id, duplicate, reason));
    }

    /** 200 when the change took effect, 202 when an upgrade awaits the checker. */
    @Override
    public ResponseEntity<Customer> customersChangeTier(UUID id, String idempotencyKey, CustomersChangeTierRequest body) {
        JsonNode req = http.tree(body);
        String toTier = req.get("to_tier").asString();
        String reason = req.get("reason").asString();
        return http.custom(idempotencyKey, "customers.change-tier:" + TenantContext.principal(), id, body, Customer.class, () -> {
            CustomerService.TierResult r = customers.changeTier(id, toTier, reason);
            return ResponseEntity.status(r.pending() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                    .body(http.read(r.customer(), Customer.class));
        });
    }

    @Override
    public ResponseEntity<CustomerKycTierHistoryPage> customersTierHistory(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(http.read((tools.jackson.databind.node.ObjectNode) queries.tierHistory(id, limit, cursor),
                CustomerKycTierHistoryPage.class));
    }

    @Override
    public ResponseEntity<CustomerRelationshipPage> customersRelationships(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(http.read((tools.jackson.databind.node.ObjectNode) queries.relationships(id, limit, cursor),
                CustomerRelationshipPage.class));
    }

    @Override
    public ResponseEntity<CustomerPage> customersSearch(Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(http.read((tools.jackson.databind.node.ObjectNode) queries.search(request.getParameter("q"), limit, cursor),
                CustomerPage.class));
    }

    /** A pure query: naturally idempotent, so the result is computed afresh rather than replayed. */
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ResponseEntity customersDuplicatesCheck(String idempotencyKey, CustomersDuplicatesCheckRequest body,
                                                   Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(queries.duplicates(http.tree(body), limit));
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ResponseEntity customersView(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok(queries.view(id));
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ResponseEntity customersOwnershipTree(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok(queries.ownershipTree(id));
    }
}
