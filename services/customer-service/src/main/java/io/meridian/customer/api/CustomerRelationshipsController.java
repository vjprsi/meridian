package io.meridian.customer.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.customer.api.generated.CustomerRelationshipsApi;
import io.meridian.customer.api.generated.model.CustomerRelationship;
import io.meridian.customer.api.generated.model.CustomerRelationshipCreate;
import io.meridian.customer.api.generated.model.CustomerRelationshipUpdate;
import io.meridian.customer.application.RelationshipService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Relationships with ownership rules (no self links, shares within 100%) and change events. */
@RestController
public class CustomerRelationshipsController implements CustomerRelationshipsApi {
    private final RelationshipService relationships;
    private final Http http;

    public CustomerRelationshipsController(RelationshipService relationships, Http http) {
        this.relationships = relationships;
        this.http = http;
    }

    @Override
    public ResponseEntity<CustomerRelationship> customerRelationshipsCreate(String idempotencyKey, CustomerRelationshipCreate body) {
        return http.created(idempotencyKey, "relationships.create", null, body, CustomerRelationship.class,
                () -> relationships.create(body));
    }

    @Override
    public ResponseEntity<CustomerRelationship> customerRelationshipsUpdate(UUID id, String ifMatch, CustomerRelationshipUpdate body) {
        TenantContext.require();
        ObjectNode row = relationships.update(id, ifMatch, body);
        return ResponseEntity.ok().eTag("\"" + row.get("row_version").asString() + "\"").body(http.read(row, CustomerRelationship.class));
    }

    @Override
    public ResponseEntity<Void> customerRelationshipsDelete(UUID id) {
        TenantContext.require();
        relationships.delete(id);
        return ResponseEntity.noContent().build();
    }
}
