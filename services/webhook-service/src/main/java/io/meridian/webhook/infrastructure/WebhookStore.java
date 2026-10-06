package io.meridian.webhook.infrastructure;

import io.meridian.common.error.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL beyond generic CRUD: locking, dispatch selection, delivery bookkeeping. All queries are tenant-scoped by RLS. */
@Repository
public class WebhookStore {
    private final JdbcClient jdbc;

    public WebhookStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record EndpointRow(UUID id, String url, String status, String secretBlob, int secretVersion,
                              String customHeaders, int consecutiveFailures, String apiVersion, int maxRps) {}

    public record DeliveryRow(UUID id, UUID endpointId, String eventId, String eventType, String orderingKey,
                              String payload, String status, int attemptCount, OffsetDateTime createdAt,
                              UUID replayJobId) {}

    public record SubscriptionRow(UUID endpointId, String eventType, String filterExpression) {}

    public record ReplayRow(UUID id, UUID endpointId, String eventTypes, OffsetDateTime from, OffsetDateTime to,
                            String status) {}

    private static final String ENDPOINT_COLS = """
            id, url, status, signing_secret, signing_secret_version, custom_headers::text, consecutive_failures,
            api_version, max_requests_per_second""";

    private static final String DELIVERY_COLS = """
            id, webhook_endpoint_id, event_id, event_type, ordering_key, payload::text, status, attempt_count,
            created_at, replay_job_id""";

    // ------------------------------------------------------------------ endpoints

    public Optional<EndpointRow> endpoint(UUID id, boolean lock) {
        return jdbc.sql("select " + ENDPOINT_COLS + " from webhook_endpoint where id = ?" + (lock ? " for update" : ""))
                .param(id)
                .query((rs, i) -> new EndpointRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getString(6), rs.getInt(7), rs.getString(8), rs.getInt(9)))
                .optional();
    }

    public EndpointRow requireEndpoint(UUID id, boolean lock) {
        return endpoint(id, lock).orElseThrow(() -> ApiException.notFound("webhook endpoint", id));
    }

    public String endpointJson(UUID id) {
        return jdbc.sql("select row_to_json(t)::text from webhook_endpoint t where id = ?").param(id)
                .query(String.class).single();
    }

    public void setEndpointStatus(UUID id, String status, String disabledReason, boolean resetFailures) {
        jdbc.sql("""
                update webhook_endpoint set status = ?, disabled_reason = ?,
                       consecutive_failures = case when ? then 0 else consecutive_failures end
                where id = ?""")
                .params(status, disabledReason, resetFailures, id).update();
    }

    public void updateSecret(UUID id, String blob, int version) {
        jdbc.sql("update webhook_endpoint set signing_secret = ?, signing_secret_version = ? where id = ?")
                .params(blob, version, id).update();
    }

    public void recordEndpointSuccess(UUID id) {
        jdbc.sql("""
                update webhook_endpoint set consecutive_failures = 0, last_success_at = now(),
                       status = case when status = 'FAILING' then 'ACTIVE' else status end
                where id = ?""").param(id).update();
    }

    public void recordEndpointFailure(UUID id, int failures, String status, String disabledReason) {
        jdbc.sql("""
                update webhook_endpoint set consecutive_failures = ?, last_failure_at = now(), status = ?,
                       disabled_reason = coalesce(?, disabled_reason)
                where id = ?""").params(failures, status, disabledReason, id).update();
    }

    public boolean endpointHasDeliveries(UUID id) {
        return jdbc.sql("select exists(select 1 from webhook_delivery where webhook_endpoint_id = ?)")
                .param(id).query(Boolean.class).single();
    }

    public void deleteEndpointCascade(UUID id) {
        jdbc.sql("delete from webhook_replay_job where webhook_endpoint_id = ?").param(id).update();
        jdbc.sql("delete from webhook_subscription where webhook_endpoint_id = ?").param(id).update();
        jdbc.sql("delete from webhook_endpoint where id = ?").param(id).update();
    }

    // ------------------------------------------------------------------ subscriptions

    public List<SubscriptionRow> activeSubscriptions() {
        return jdbc.sql("""
                select s.webhook_endpoint_id, s.event_type, s.filter_expression
                from webhook_subscription s join webhook_endpoint e on e.id = s.webhook_endpoint_id
                where s.is_active and e.status <> 'DISABLED'
                order by s.webhook_endpoint_id, s.id""")
                .query((rs, i) -> new SubscriptionRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .list();
    }

    public List<String> activeSubscriptionPatterns(UUID endpointId) {
        return jdbc.sql("select event_type from webhook_subscription where webhook_endpoint_id = ? and is_active")
                .param(endpointId).query(String.class).list();
    }

    // ------------------------------------------------------------------ deliveries

    public Optional<DeliveryRow> delivery(UUID id, boolean lock) {
        return jdbc.sql("select " + DELIVERY_COLS + " from webhook_delivery where id = ?" + (lock ? " for update" : ""))
                .param(id).query(this::deliveryRow).optional();
    }

    public DeliveryRow requireDelivery(UUID id, boolean lock) {
        return delivery(id, lock).orElseThrow(() -> ApiException.notFound("webhook delivery", id));
    }

    public String deliveryJson(UUID id) {
        return jdbc.sql("select row_to_json(t)::text from webhook_delivery t where id = ?").param(id)
                .query(String.class).single();
    }

    /** Inserts a PENDING delivery unless the endpoint already has one for this event (and replay job). */
    public Optional<UUID> insertDeliveryIfAbsent(UUID tenant, String principal, UUID id, UUID endpointId, String eventId,
                                                 String eventType, String orderingKey, String payloadJson,
                                                 UUID replayJobId) {
        return jdbc.sql("""
                insert into webhook_delivery (id, tenant_id, created_by, updated_by, webhook_endpoint_id, event_id,
                       event_type, ordering_key, payload, status, replay_job_id)
                select ?::uuid, ?::uuid, ?, ?, ?::uuid, ?, ?, ?, ?::jsonb, 'PENDING', ?::uuid
                where not exists (select 1 from webhook_delivery
                                  where webhook_endpoint_id = ?::uuid and event_id = ?
                                    and replay_job_id is not distinct from ?::uuid)
                returning id""")
                .params(id, tenant, principal, principal, endpointId, eventId, eventType, orderingKey, payloadJson,
                        replayJobId, endpointId, eventId, replayJobId)
                .query(UUID.class).optional();
    }

    /**
     * Claims a due delivery for dispatch by pushing its next_attempt_at forward by a lease; only one dispatcher
     * wins. Deliveries wait while an older delivery with the same ordering key is still unfinished.
     */
    public boolean claimDue(UUID id, int leaseSeconds) {
        return jdbc.sql("""
                update webhook_delivery d set next_attempt_at = now() + make_interval(secs => ?)
                where d.id = ? and d.status in ('PENDING', 'RETRYING')
                  and (d.next_attempt_at is null or d.next_attempt_at <= now())""")
                .params(leaseSeconds, id).update() == 1;
    }

    public void lease(UUID id, String status, int leaseSeconds) {
        jdbc.sql("update webhook_delivery set status = ?, next_attempt_at = now() + make_interval(secs => ?) where id = ?")
                .params(status, leaseSeconds, id).update();
    }

    public List<UUID> dueDeliveries(int limit) {
        return jdbc.sql("""
                select d.id from webhook_delivery d join webhook_endpoint e on e.id = d.webhook_endpoint_id
                where d.status in ('PENDING', 'RETRYING')
                  and (d.next_attempt_at is null or d.next_attempt_at <= now())
                  and e.status <> 'DISABLED'
                  and not exists (
                      select 1 from webhook_delivery p
                      where p.webhook_endpoint_id = d.webhook_endpoint_id and d.ordering_key is not null
                        and p.ordering_key = d.ordering_key
                        and p.replay_job_id is not distinct from d.replay_job_id
                        and p.status in ('PENDING', 'RETRYING') and p.id < d.id)
                order by d.id limit ?""")
                .param(limit).query(UUID.class).list();
    }

    public void insertAttempt(UUID tenant, String principal, UUID deliveryId, int attemptNo, String headersJson,
                              Integer statusCode, String responseBody, Long latencyMs, String error) {
        jdbc.sql("""
                insert into webhook_delivery_attempt (tenant_id, created_by, webhook_delivery_id, attempt_no,
                       request_headers, status_code, response_body, latency_ms, error)
                values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""")
                .params(tenant, principal, deliveryId, attemptNo, headersJson, statusCode, responseBody,
                        latencyMs == null ? null : (int) Math.min(Integer.MAX_VALUE, latencyMs), error)
                .update();
    }

    public void completeAttempt(UUID id, int attemptCount, String status, OffsetDateTime nextAttemptAt,
                                Integer statusCode, String error, boolean delivered) {
        jdbc.sql("""
                update webhook_delivery set attempt_count = ?, status = ?, next_attempt_at = ?, last_status_code = ?,
                       last_error = ?, delivered_at = case when ? then now() else delivered_at end
                where id = ?""")
                .params(attemptCount, status, nextAttemptAt, statusCode, error, delivered, id).update();
    }

    private DeliveryRow deliveryRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new DeliveryRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8),
                rs.getObject(9, OffsetDateTime.class), rs.getObject(10, UUID.class));
    }

    // ------------------------------------------------------------------ replay jobs

    public Optional<ReplayRow> replay(UUID id, boolean lock) {
        return jdbc.sql("""
                select id, webhook_endpoint_id, event_types::text, from_time, to_time, status
                from webhook_replay_job where id = ?""" + (lock ? " for update" : ""))
                .param(id)
                .query((rs, i) -> new ReplayRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class),
                        rs.getString(6)))
                .optional();
    }

    public String replayJson(UUID id) {
        return jdbc.sql("select row_to_json(t)::text from webhook_replay_job t where id = ?").param(id)
                .query(String.class).single();
    }

    public List<UUID> queuedReplays() {
        return jdbc.sql("select id from webhook_replay_job where status = 'QUEUED' order by id").query(UUID.class).list();
    }

    public void setReplayStatus(UUID id, String status, Integer total, Integer replayed) {
        jdbc.sql("""
                update webhook_replay_job set status = ?, events_total = coalesce(?, events_total),
                       events_replayed = coalesce(?, events_replayed)
                where id = ?""").params(status, total, replayed, id).update();
    }

    /** Original (non-replay, non-test) deliveries of an endpoint created within [from, to). */
    public List<DeliveryRow> originalDeliveries(UUID endpointId, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("select " + DELIVERY_COLS + """
                 from webhook_delivery
                where webhook_endpoint_id = ? and replay_job_id is null and created_at >= ? and created_at < ?
                  and event_id not like 'evt_test_%'
                order by id""")
                .params(endpointId, from, to).query(this::deliveryRow).list();
    }

    public List<UUID> tenantsWithActivity() {
        return jdbc.sql("select distinct tenant_id from idempotency_key").query(UUID.class).list();
    }
}
