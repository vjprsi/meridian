package io.meridian.payment.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.config.PaymentProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-ETM-1 (G-08): drives Order &amp; Pay (ETM) ambiguous-case settlement on a schedule, per tenant. Each pass runs the
 * idempotent {@link OrderService#resolveDueAmbiguous()} under the tenant's context, so every due ambiguous order is
 * resolved (retry / wait / roll-forward / rollback) deterministically from its payment-gateway heartbeat and fulfilment
 * deadline - a fulfilment timeout therefore auto-resolves a stuck order rather than leaving it pending. Opt-in per
 * deployment ({@code meridian.payment.etm.schedule-enabled=true}) and additionally gated at runtime by
 * {@code meridian.payment.etm.enabled}; tenants are discovered from the RLS-free {@code idempotency_key} table, as the
 * other per-tenant jobs do. The same work is callable over HTTP (POST /v1/payment-orders/resolve-ambiguous).
 */
@Component
@ConditionalOnProperty(name = "meridian.payment.etm.schedule-enabled", havingValue = "true")
public class AmbiguousSettlementResolver {
    private static final Logger log = LoggerFactory.getLogger(AmbiguousSettlementResolver.class);
    static final String PRINCIPAL = "job:etm-ambiguous-settlement";

    private final JdbcClient jdbc;
    private final OrderService orders;
    private final PaymentProperties props;

    public AmbiguousSettlementResolver(JdbcClient jdbc, OrderService orders, PaymentProperties props) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${meridian.payment.etm.interval-ms:60000}", initialDelay = 120_000)
    public void scheduled() {
        if (props.etm().enabled()) {
            runAllTenants();
        }
    }

    /** One pass over every tenant with activity; returns the number of ambiguous orders resolved. */
    public int runAllTenants() {
        int total = 0;
        for (UUID tenant : jdbc.sql("select distinct tenant_id from idempotency_key").query(UUID.class).list()) {
            try {
                ObjectNode r = TenantContext.callAs(tenant, PRINCIPAL, orders::resolveDueAmbiguous);
                int resolved = r.path("resolved").asInt();
                total += resolved;
                if (resolved > 0 || !r.path("failures").isEmpty()) {
                    log.info("Tenant {}: ETM ambiguous settlement - {} rolled forward, {} rolled back, {} retried, "
                                    + "{} waiting, {} failed", tenant, r.path("rolled_forward").asInt(),
                            r.path("rolled_back").asInt(), r.path("retried").asInt(), r.path("waited").asInt(),
                            r.path("failures").size());
                }
            } catch (Exception e) {
                log.warn("ETM ambiguous settlement failed for tenant {}: {}", tenant, e.toString());
            }
        }
        return total;
    }
}
