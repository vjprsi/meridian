package io.meridian.inbox.application;

import io.meridian.common.error.ApiException;
import java.util.UUID;

/**
 * Who is calling, derived server-side (never from the request body): a customer relayed by the customer channel
 * service, or a bank-side principal (staff user or service). Customers are confined to their own threads.
 *
 * @param customerId the relayed customer (customer callers only)
 * @param userId     the staff user id when the principal is a UUID (bank-side callers only), else null
 */
public record Caller(boolean customer, UUID customerId, UUID userId) {
    public static Caller customer(UUID customerId) {
        return new Caller(true, customerId, null);
    }

    public static Caller staff(UUID userId) {
        return new Caller(false, null, userId);
    }

    /** Stable identity for idempotency fingerprints, so a key replay never crosses callers. */
    public String key() {
        return customer ? "customer:" + customerId : "staff";
    }

    /** 403 for customer callers on staff-only operations. */
    public void requireStaff() {
        if (customer) {
            throw new ApiException(403, "staff_only", "This operation is available to bank staff only");
        }
    }

    /** Customers may only act on their own threads; another customer's thread does not exist for them (404). */
    public void requireOwner(String what, UUID id, String ownerCustomerId) {
        if (customer && !customerId.toString().equals(ownerCustomerId)) {
            throw ApiException.notFound(what, id);
        }
    }

    /** A relayed customer may only name itself as the customer (422 customer_mismatch otherwise). */
    public UUID customerFor(UUID requested) {
        if (!customer) {
            return requested;
        }
        if (requested != null && !requested.equals(customerId)) {
            throw ApiException.unprocessable("customer_mismatch", "customer_id must be the calling customer");
        }
        return customerId;
    }
}
