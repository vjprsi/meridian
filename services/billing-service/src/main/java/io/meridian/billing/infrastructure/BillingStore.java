package io.meridian.billing.infrastructure;

import io.meridian.common.error.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Billing SQL beyond generic CRUD. Rows are returned as row_to_json text; all values are bind parameters. */
@Repository
public class BillingStore {
    private final JdbcClient jdbc;

    public BillingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> plan(UUID id) {
        return jdbc.sql("select row_to_json(p)::text from price_plan p where id = ?").param(id).query(String.class).optional();
    }

    public String lockPlan(UUID id) {
        return jdbc.sql("select row_to_json(p)::text from price_plan p where id = ? for update").param(id)
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("price plan", id));
    }

    public String lockSubscription(UUID id) {
        return jdbc.sql("select row_to_json(s)::text from tenant_subscription s where id = ? for update").param(id)
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("subscription", id));
    }

    public String subscription(UUID id) {
        return jdbc.sql("select row_to_json(s)::text from tenant_subscription s where id = ?").param(id)
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("subscription", id));
    }

    public String lockInvoice(UUID id) {
        return jdbc.sql("select row_to_json(i)::text from tenant_invoice i where id = ? for update").param(id)
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("invoice", id));
    }

    public String invoice(UUID id) {
        return jdbc.sql("select row_to_json(i)::text from tenant_invoice i where id = ?").param(id)
                .query(String.class).optional().orElseThrow(() -> ApiException.notFound("invoice", id));
    }

    /** Serialises writers of one tenant (subscription uniqueness, invoice numbering) for the current transaction. */
    public void lockTenant(String purpose, UUID tenant) {
        jdbc.sql("select 1 from (select pg_advisory_xact_lock(hashtext(?))) l").param("billing:" + purpose + ":" + tenant)
                .query(Integer.class).single();
    }

    public boolean hasOpenSubscription() {
        return jdbc.sql("select count(*) from tenant_subscription where status <> 'CANCELLED'")
                .query(Long.class).single() > 0;
    }

    /** Subscriptions that are billable for any day of [from, to] (trials are not billed). */
    public List<String> billableSubscriptions(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select row_to_json(s)::text from tenant_subscription s
                where s.status in ('ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELLED')
                  and s.start_date <= ? and (s.end_date is null or s.end_date >= ?)
                order by s.id""")
                .params(to, from).query(String.class).list();
    }

    public boolean invoicedOverlapping(UUID subscriptionId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select count(*) from tenant_invoice where tenant_subscription_id = ? and status <> 'VOID'
                  and period_start <= ? and period_end >= ?""")
                .params(subscriptionId, to, from).query(Long.class).single() > 0;
    }

    public boolean invoicedOnOrAfter(UUID subscriptionId, LocalDate date) {
        return jdbc.sql("""
                select count(*) from tenant_invoice where tenant_subscription_id = ? and status <> 'VOID'
                  and period_end >= ?""")
                .params(subscriptionId, date).query(Long.class).single() > 0;
    }

    public long countInvoiceNumbers(String prefix) {
        return jdbc.sql("select count(*) from tenant_invoice where invoice_number like ?")
                .param(prefix + "%").query(Long.class).single();
    }

    public record MetricTotal(String metric, long total, long peakDaily, int days) {}

    /** Per metric: total quantity and the peak of daily totals within [from, to]. */
    public List<MetricTotal> usageTotals(LocalDate from, LocalDate to, String serviceName) {
        return jdbc.sql("""
                select metric, sum(q)::bigint, max(q)::bigint, count(*)::int from (
                  select metric, period_date, sum(quantity) q from usage_record
                  where period_date between ? and ? and (?::varchar is null or service_name = ?::varchar)
                  group by metric, period_date) d
                group by metric order by metric""")
                .params(from, to, serviceName, serviceName)
                .query((rs, i) -> new MetricTotal(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getInt(4)))
                .list();
    }

    public Optional<String> usageByKey(String key) {
        return jdbc.sql("select row_to_json(u)::text from usage_record u where idempotency_key = ?").param(key)
                .query(String.class).optional();
    }

    public long overdueInvoices(UUID subscriptionId) {
        return jdbc.sql("select count(*) from tenant_invoice where tenant_subscription_id = ? and status = 'OVERDUE'")
                .param(subscriptionId).query(Long.class).single();
    }

    /** Marks issued invoices past their due date OVERDUE; returns the updated invoice rows. */
    public List<String> markOverdue(LocalDate today) {
        return jdbc.sql("""
                with u as (
                  update tenant_invoice set status = 'OVERDUE', updated_by = 'system', updated_at = now(),
                         row_version = row_version + 1
                  where status in ('ISSUED', 'PARTIALLY_PAID') and due_date < ?
                  returning *)
                select row_to_json(u)::text from u order by id""")
                .param(today).query(String.class).list();
    }

    public List<UUID> tenantsWithActivity() {
        return jdbc.sql("select distinct tenant_id from idempotency_key").query(UUID.class).list();
    }
}
