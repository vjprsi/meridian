package io.meridian.web.tenancy;

import io.meridian.common.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Sets {@code app.tenant_id} on every connection handed out, so PostgreSQL row-level security scopes all
 * queries to the current tenant. Pooled connections are always re-stamped, so no tenant leaks between requests.
 * Without a bound tenant the nil UUID is used, which matches no rows.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return stamp(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return stamp(super.getConnection(username, password));
    }

    private static Connection stamp(Connection c) throws SQLException {
        UUID tenant = TenantContext.current().orElse(TenantContext.NO_TENANT);
        try (PreparedStatement ps = c.prepareStatement("select set_config('app.tenant_id', ?, false)")) {
            ps.setString(1, tenant.toString());
            ps.execute();
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }
}
