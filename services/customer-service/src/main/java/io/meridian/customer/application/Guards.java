package io.meridian.customer.application;

import io.meridian.common.error.ApiException;
import io.meridian.customer.domain.Lifecycles;
import io.meridian.customer.infrastructure.Rows;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/** Preconditions shared by the use cases. */
@Component
public class Guards {
    private final Rows rows;

    public Guards(Rows rows) {
        this.rows = rows;
    }

    /** The customer, which must still have an open relationship (not CLOSED or DECEASED). */
    public ObjectNode openCustomer(UUID customerId) {
        ObjectNode c = existingCustomer(customerId);
        if (!Lifecycles.CUSTOMER_OPEN.contains(c.get("status").asString())) {
            throw ApiException.conflict("customer_closed", "Customer is " + c.get("status").asString());
        }
        return c;
    }

    public ObjectNode activeCustomer(UUID customerId) {
        ObjectNode c = existingCustomer(customerId);
        if (!"ACTIVE".equals(c.get("status").asString())) {
            throw ApiException.conflict("customer_not_active", "Customer must be ACTIVE, is " + c.get("status").asString());
        }
        return c;
    }

    public ObjectNode existingCustomer(UUID customerId) {
        return rows.find("customer", customerId)
                .orElseThrow(() -> ApiException.notFound("customer", customerId));
    }

    /** A child row that must belong to the customer in the path. */
    public ObjectNode child(String table, UUID customerId, UUID id, boolean lock) {
        ObjectNode row = lock ? rows.lock(table, id) : rows.get(table, id);
        if (!customerId.toString().equals(row.get("customer_id").asString())) {
            throw ApiException.notFound(table.replace('_', ' '), id);
        }
        return row;
    }
}
