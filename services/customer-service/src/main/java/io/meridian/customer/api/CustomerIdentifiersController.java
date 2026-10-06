package io.meridian.customer.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.customer.api.generated.CustomerIdentifiersApi;
import io.meridian.customer.api.generated.model.CustomerIdentifier;
import io.meridian.customer.api.generated.model.CustomerIdentifierCreate;
import io.meridian.customer.api.generated.model.CustomerIdentifierUpdate;
import io.meridian.customer.application.IdentifierService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Identifiers: create/update compute the keyed value hash used for search and duplicate detection. */
@RestController
public class CustomerIdentifiersController implements CustomerIdentifiersApi {
    private final IdentifierService identifiers;
    private final Http http;

    public CustomerIdentifiersController(IdentifierService identifiers, Http http) {
        this.identifiers = identifiers;
        this.http = http;
    }

    @Override
    public ResponseEntity<CustomerIdentifier> customerIdentifiersCreate(UUID customerId, String idempotencyKey,
                                                                        CustomerIdentifierCreate body) {
        return http.created(idempotencyKey, "identifiers.create", customerId, body, CustomerIdentifier.class,
                () -> identifiers.create(customerId, body));
    }

    @Override
    public ResponseEntity<CustomerIdentifier> customerIdentifiersUpdate(UUID customerId, UUID id, String ifMatch,
                                                                        CustomerIdentifierUpdate body) {
        TenantContext.require();
        ObjectNode row = identifiers.update(customerId, id, ifMatch, body);
        return ResponseEntity.ok().eTag("\"" + row.get("row_version").asString() + "\"").body(http.read(row, CustomerIdentifier.class));
    }
}
