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
import io.meridian.customer.api.generated.model.CustomersContactSearchBackfillRequest;
import io.meridian.customer.api.generated.model.CustomersDuplicatesCheckRequest;
import io.meridian.customer.api.generated.model.CustomersMarkDeceasedRequest;
import io.meridian.customer.api.generated.model.CustomersMergeRequest;
import io.meridian.customer.api.generated.model.CustomersReactivateRequest;
import io.meridian.customer.api.generated.model.CustomersSuspendRequest;
import io.meridian.customer.application.ContactService;
import io.meridian.customer.application.CustomerQueryService;
import io.meridian.customer.application.CustomerService;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Customers: master data, lifecycle actions, KYC tier (maker-checker through workflow-service), merge, search,
 * duplicates and the single customer view. Contract: generated/openapi/services/customer-service.openapi.yaml.
 */
@RestController
public class CustomersController implements CustomersApi {
    private final CustomerService customers;
    private final CustomerQueryService queries;
    private final ContactService contacts;
    private final Http http;

    public CustomersController(CustomerService customers, CustomerQueryService queries, ContactService contacts, Http http) {
        this.customers = customers;
        this.queries = queries;
        this.contacts = contacts;
        this.http = http;
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
        return http.ok(idempotencyKey, "customers.suspend", id, body, Customer.class,
                () -> customers.suspend(id, body.getReason()));
    }

    @Override
    public ResponseEntity<Customer> customersReactivate(UUID id, String idempotencyKey, CustomersReactivateRequest body) {
        return http.ok(idempotencyKey, "customers.reactivate", id, body, Customer.class,
                () -> customers.reactivate(id, body.getReason()));
    }

    @Override
    public ResponseEntity<Customer> customersClose(UUID id, String idempotencyKey, CustomersCloseRequest body) {
        return http.ok(idempotencyKey, "customers.close", id, body, Customer.class, () -> customers.close(id, body.getReason()));
    }

    @Override
    public ResponseEntity<Customer> customersMarkDeceased(UUID id, String idempotencyKey, CustomersMarkDeceasedRequest body) {
        LocalDate on = body.getDeceasedOn();
        UUID document = body.getDocumentId();
        return http.ok(idempotencyKey, "customers.mark-deceased", id, body, Customer.class,
                () -> customers.markDeceased(id, on, document));
    }

    @Override
    public ResponseEntity<Customer> customersMerge(UUID id, String idempotencyKey, CustomersMergeRequest body) {
        return http.ok(idempotencyKey, "customers.merge", id, body, Customer.class,
                () -> customers.merge(id, body.getDuplicateCustomerId(), body.getReason()));
    }

    /** 200 when the change took effect (downgrade), 202 when an upgrade awaits approval in workflow-service. */
    @Override
    public ResponseEntity<Customer> customersChangeTier(UUID id, String idempotencyKey, CustomersChangeTierRequest body) {
        String toTier = body.getToTier().getValue();
        String reason = body.getReason();
        return http.custom(idempotencyKey, "customers.change-tier", id, body, Customer.class, () -> {
            CustomerService.TierResult r = customers.changeTier(id, toTier, reason, idempotencyKey);
            return ResponseEntity.status(r.pending() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                    .body(http.read(r.customer(), Customer.class));
        });
    }

    @Override
    public ResponseEntity<CustomerKycTierHistoryPage> customersTierHistory(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(http.read((ObjectNode) queries.tierHistory(id, limit, cursor), CustomerKycTierHistoryPage.class));
    }

    @Override
    public ResponseEntity<CustomerRelationshipPage> customersRelationships(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(http.read((ObjectNode) queries.relationships(id, limit, cursor), CustomerRelationshipPage.class));
    }

    @Override
    public ResponseEntity<CustomerPage> customersSearch(String q, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(http.read((ObjectNode) queries.search(q, limit, cursor), CustomerPage.class));
    }

    /** A pure query: naturally idempotent, so the result is computed afresh rather than replayed. */
    @Override
    public ResponseEntity<Map<String, Object>> customersDuplicatesCheck(String idempotencyKey,
                                                                        CustomersDuplicatesCheckRequest body) {
        TenantContext.require();
        JsonNode req = http.tree(body);
        return ResponseEntity.ok(http.map(queries.duplicates(req, null)));
    }

    /** Operator batch: fills contact search fields and republishes verified contacts (idempotent, resumable). */
    @Override
    public ResponseEntity<Map<String, Object>> customersContactSearchBackfill(String idempotencyKey,
                                                                              CustomersContactSearchBackfillRequest body) {
        TenantContext.require();
        JsonNode req = http.tree(body);
        Integer size = req.hasNonNull("batch_size") ? req.get("batch_size").asInt() : null;
        UUID after = req.hasNonNull("after_id") ? UUID.fromString(req.get("after_id").asString()) : null;
        return ResponseEntity.ok(contacts.backfillSearch(size, after, req.path("rehash_all").asBoolean(false)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> customersView(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok(http.map(queries.view(id)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> customersOwnershipTree(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok(http.map(queries.ownershipTree(id)));
    }
}
