package io.meridian.common.tenancy;

import io.meridian.common.error.ApiException;

/**
 * SEC-35: the one maker-checker check. Fails closed: a change whose maker is not recorded cannot be approved
 * (422 {@code four_eyes_maker_unknown}), and the checker must be a different, known principal
 * (422 {@code four_eyes_violation}).
 */
public final class FourEyes {
    /** Principal prefix of a Meridian service acting without a user ({@code svc:<client>}, see TenantFilter). */
    public static final String SERVICE_PREFIX = "svc:";

    private FourEyes() {}

    /** Requires {@code checker} to differ from a recorded {@code maker}. */
    public static void require(String maker, String checker, String message) {
        if (maker == null || maker.isBlank()) {
            throw ApiException.unprocessable("four_eyes_maker_unknown",
                    "The maker of this change is not recorded, so it cannot be approved under four-eyes");
        }
        if (checker == null || checker.isBlank() || maker.equals(checker)) {
            throw ApiException.unprocessable("four_eyes_violation", message);
        }
    }

    /** {@link #require(String, String, String)} with the current principal as checker. */
    public static void require(String maker, String message) {
        require(maker, TenantContext.principal(), message);
    }

    /** True for service principals ({@code svc:...}, legacy {@code service:...}) and the anonymous / system ones. */
    public static boolean isService(String principal) {
        return principal == null || principal.startsWith(SERVICE_PREFIX) || principal.startsWith("service:")
                || principal.equals("system") || principal.equals("anonymous");
    }
}
