package io.meridian.identity.domain;

import java.util.List;
import java.util.Optional;

/**
 * Machine-to-machine business API clients (docs/BUSINESS_M2M_API.md). A client bound to a business
 * ({@code business_customer_id}) acts as one TECHNICAL portal user of that business ({@code business_user_id}): the
 * identity provider issues it client-credentials tokens with both ids as claims, scope {@value #BUSINESS_SCOPE} and the
 * {@code m2m} marker. Such a client is a confidential server client that holds the business scope only (never a staff
 * domain scope), and the business scope is only ever held by a client bound to a business (an unbound client with it
 * would get tokens the business channel refuses).
 */
public final class BusinessClientBinding {
    public static final String BUSINESS_SCOPE = "channels:business";

    private BusinessClientBinding() {}

    /** The problem (code: message) with a client's business binding, or empty when it is consistent. */
    public static Optional<String[]> problem(String businessCustomerId, String businessUserId, String clientType,
                                             List<String> scopes) {
        boolean bound = businessCustomerId != null;
        if (bound != (businessUserId != null)) {
            return Optional.of(new String[] {"business_binding_incomplete",
                    "business_customer_id and business_user_id are set together (the business and its technical user)"});
        }
        if (bound && !"SERVER".equals(clientType)) {
            return Optional.of(new String[] {"business_client_type", "A business API client is a SERVER client"});
        }
        if (bound && scopes.stream().anyMatch(s -> !BUSINESS_SCOPE.equals(s))) {
            return Optional.of(new String[] {"business_client_scopes", "A business API client holds " + BUSINESS_SCOPE
                    + " only"});
        }
        if (!bound && scopes.contains(BUSINESS_SCOPE)) {
            return Optional.of(new String[] {"business_binding_required", BUSINESS_SCOPE
                    + " is only for clients bound to a business (business_customer_id, business_user_id)"});
        }
        return Optional.empty();
    }
}
