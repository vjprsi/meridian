package io.meridian.ledger;

import io.meridian.test.MeridianTestContainers;

/** Test-only access to the shared Postgres container's admin JDBC URL (for tamper simulations). */
final class MeridianTestContainersAccess {
    private MeridianTestContainersAccess() {}

    static String adminUrl() {
        return MeridianTestContainers.adminJdbcUrl();
    }
}
