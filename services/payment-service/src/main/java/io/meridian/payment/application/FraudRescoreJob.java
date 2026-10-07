package io.meridian.payment.application;

import static io.meridian.payment.infrastructure.Rows.str;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.payment.config.PaymentProperties;
import io.meridian.payment.infrastructure.Rows;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/**
 * Re-scores payments held because fraud scoring was unavailable ({@code ON_HOLD}, {@code hold_reason FRAUD_REVIEW},
 * {@code fraud_degraded}) once their {@code fraud_next_rescore_at} is due: exponential backoff from
 * {@code meridian.payment.fraud.auto-rescore.initial-backoff} up to {@code max-backoff}, at most {@code max-attempts}
 * automatic attempts. Each attempt is {@link PaymentProcessor#fraudRescore} with the payment's own idempotency key
 * {@code pay-fraud:<id>}, so fraud-service replays an evaluation it already completed and a payment is never scored
 * twice. Runs from the internal scheduler for every tenant with activity (tenants are discovered from the RLS-free
 * {@code idempotency_key} table, as other per-tenant jobs do) and from {@code POST /v1/payments/execute-due}. A hold
 * is claimed with a conditional update first, so two replicas never re-score the same payment at the same time.
 */
@Component
public class FraudRescoreJob {
    private static final Logger log = LoggerFactory.getLogger(FraudRescoreJob.class);
    static final String PRINCIPAL = "job:fraud-rescore";

    private final Rows rows;
    private final JdbcClient jdbc;
    private final PaymentProcessor processor;
    private final PaymentProperties props;

    public FraudRescoreJob(Rows rows, JdbcClient jdbc, PaymentProcessor processor, PaymentProperties props) {
        this.rows = rows;
        this.jdbc = jdbc;
        this.processor = processor;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${meridian.payment.fraud.auto-rescore.interval-ms:60000}",
            initialDelayString = "${meridian.payment.fraud.auto-rescore.interval-ms:60000}")
    public void scheduled() {
        if (props.fraud().enabled() && props.fraud().autoRescore().enabled()) {
            runAllTenants();
        }
    }

    /** One pass over every tenant with activity; returns the number of holds re-scored. */
    public int runAllTenants() {
        int total = 0;
        for (UUID tenant : jdbc.sql("select distinct tenant_id from idempotency_key").query(UUID.class).list()) {
            try {
                ObjectNode r = TenantContext.callAs(tenant, PRINCIPAL, this::rescoreDue);
                int n = r.path("rescored").asInt();
                total += n;
                if (n > 0) {
                    log.info("Tenant {}: {} degraded fraud hold(s) re-scored ({} released, {} still held, {} rejected)",
                            tenant, n, r.path("released").asInt(), r.path("held").asInt(), r.path("rejected").asInt());
                }
            } catch (Exception e) {
                log.warn("Fraud re-scoring failed for tenant {}: {}", tenant, e.toString());
            }
        }
        return total;
    }

    /** Re-scores the bound tenant's due degraded holds (bounded batch). */
    public ObjectNode rescoreDue() {
        PaymentProperties.Fraud.AutoRescore cfg = props.fraud().autoRescore();
        ObjectNode out = rows.object();
        int rescored = 0;
        int released = 0;
        int held = 0;
        int rejected = 0;
        if (!props.fraud().enabled()) {
            return out.put("rescored", 0);
        }
        List<ObjectNode> due = rows.query("""
                select row_to_json(t)::text from payment t
                where status = 'ON_HOLD' and hold_reason = 'FRAUD_REVIEW' and fraud_degraded
                  and fraud_next_rescore_at is not null and fraud_next_rescore_at <= ?
                order by fraud_next_rescore_at limit ?""", rows.now(), cfg.batchSize());
        var failures = out.putArray("failures");
        for (ObjectNode p : due) {
            UUID id = UUID.fromString(str(p, "id"));
            // claim: push the next attempt out by one backoff step unless another replica already did
            int claimed = rows.execute("""
                    update payment set fraud_next_rescore_at = ?
                    where id = ? and status = 'ON_HOLD' and fraud_degraded and fraud_next_rescore_at <= ?""",
                    rows.now().plus(cfg.backoff(Rows.integer(p, "fraud_rescore_attempts"))), id, rows.now());
            if (claimed != 1) {
                continue;
            }
            try {
                ObjectNode r = processor.fraudRescore(id);
                rescored++;
                switch (str(r, "status")) {
                    case "ON_HOLD" -> held++;
                    case "REJECTED" -> rejected++;
                    default -> released++;
                }
            } catch (ApiException e) {
                failures.addObject().put("payment_id", id.toString()).put("code", e.code()).put("message", e.getMessage());
            }
        }
        out.put("candidates", due.size());
        out.put("rescored", rescored);
        out.put("released", released);
        out.put("held", held);
        out.put("rejected", rejected);
        return out;
    }
}
