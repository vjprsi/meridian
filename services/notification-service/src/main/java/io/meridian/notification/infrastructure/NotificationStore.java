package io.meridian.notification.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Notification-specific SQL beyond the generic CRUD engine. All statements are tenant-scoped by RLS. */
@Repository
public class NotificationStore {
    private final JdbcClient jdbc;

    public NotificationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> json(String table, UUID id) {
        return jdbc.sql("select row_to_json(t)::text from " + table(table) + " t where id = ?")
                .param(id).query(String.class).optional();
    }

    /** Locks the notification row for a state change. */
    public Optional<String> lockNotification(UUID id) {
        return jdbc.sql("select row_to_json(t)::text from notification t where id = ? for update")
                .param(id).query(String.class).optional();
    }

    public Optional<String> lockTemplate(UUID id) {
        return jdbc.sql("select row_to_json(t)::text from notification_template t where id = ? for update")
                .param(id).query(String.class).optional();
    }

    public int nextTemplateVersion(String code, String channel, String locale) {
        Integer max = jdbc.sql("""
                select max(version) from notification_template where code = ? and channel = ? and locale = ?""")
                .params(code, channel, locale).query(Integer.class).optional().orElse(null);
        return max == null ? 1 : max + 1;
    }

    /** Active template of the code/channel for the first locale that has one. */
    public Optional<String> activeTemplate(String code, String channel, List<String> locales) {
        for (String locale : locales) {
            Optional<String> t = jdbc.sql("""
                    select row_to_json(t)::text from notification_template t
                    where code = ? and channel = ? and locale = ? and status = 'ACTIVE'
                    order by version desc limit 1""")
                    .params(code, channel, locale).query(String.class).optional();
            if (t.isPresent()) {
                return t;
            }
        }
        return Optional.empty();
    }

    public List<String> activeTemplateChannels(String code) {
        return jdbc.sql("select distinct channel from notification_template where code = ? and status = 'ACTIVE'")
                .param(code).query(String.class).list();
    }

    public void retireOtherActive(UUID keepId, String code, String channel, String locale) {
        jdbc.sql("""
                update notification_template set status = 'RETIRED'
                where code = ? and channel = ? and locale = ? and status = 'ACTIVE' and id <> ?""")
                .params(code, channel, locale, keepId).update();
    }

    public void setTemplateStatus(UUID id, String status) {
        jdbc.sql("update notification_template set status = ? where id = ?").params(status, id).update();
    }

    public Optional<String> preference(String recipientType, UUID recipientId, String category, String channel) {
        if (recipientId == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                select row_to_json(p)::text from notification_preference p
                where recipient_type = ? and recipient_id = ? and category = ? and channel = ?""")
                .params(recipientType, recipientId, category, channel).query(String.class).optional();
    }

    /** Any preference of the recipient carrying a preferred locale. */
    public Optional<String> preferredLocale(String recipientType, UUID recipientId) {
        return jdbc.sql("""
                select locale from notification_preference
                where recipient_type = ? and recipient_id = ? and locale is not null
                order by updated_at desc limit 1""")
                .params(recipientType, recipientId).query(String.class).optional();
    }

    /** Providers usable for a channel: ACTIVE before TESTING, then by priority. */
    public List<String> providersFor(String channel) {
        return jdbc.sql("""
                select row_to_json(p)::text from notification_provider p
                where channel = ? and status in ('ACTIVE', 'TESTING')
                order by case status when 'ACTIVE' then 0 else 1 end, priority, created_at""")
                .param(channel).query(String.class).list();
    }

    public Optional<String> byProviderMessageId(String providerMessageId) {
        return jdbc.sql("""
                select row_to_json(t)::text from notification t where provider_message_id = ? for update""")
                .param(providerMessageId).query(String.class).optional();
    }

    public Optional<String> duplicate(String sourceEventId, String channel, String recipientType, UUID recipientId,
                                      String destination) {
        return jdbc.sql("""
                select row_to_json(t)::text from notification t
                where source_event_id = ? and channel = ? and recipient_type = ?
                  and recipient_id is not distinct from ? and destination = ?
                order by id limit 1""")
                .params(sourceEventId, channel, recipientType, recipientId, destination)
                .query(String.class).optional();
    }

    public int attemptCount(UUID notificationId, int cycle) {
        return jdbc.sql("""
                select count(*) from notification_delivery_attempt
                where notification_id = ? and cycle = ?""")
                .params(notificationId, cycle).query(Integer.class).single();
    }

    public int totalAttempts(UUID notificationId) {
        return jdbc.sql("select count(*) from notification_delivery_attempt where notification_id = ?")
                .param(notificationId).query(Integer.class).single();
    }

    public int currentCycle(UUID notificationId) {
        return jdbc.sql("""
                select coalesce(max(cycle), 1) from notification_delivery_attempt
                where notification_id = ?""")
                .param(notificationId).query(Integer.class).single();
    }

    public void update(UUID id, String status, String sqlSetExtra, Object... extraParams) {
        Object[] params = new Object[extraParams.length + 2];
        params[0] = status;
        System.arraycopy(extraParams, 0, params, 1, extraParams.length);
        params[params.length - 1] = id;
        jdbc.sql("update notification set status = ?" + (sqlSetExtra.isEmpty() ? "" : ", " + sqlSetExtra) + " where id = ?")
                .params(params).update();
    }

    /** Notifications whose next send time has come (scheduled, or queued for a retry). */
    public List<UUID> due(Instant now, int limit) {
        return jdbc.sql("""
                select id from notification
                where status in ('QUEUED', 'SCHEDULED') and (scheduled_at is null or scheduled_at <= ?)
                order by coalesce(scheduled_at, created_at) limit ?""")
                .params(OffsetDateTime.ofInstant(now, ZoneOffset.UTC), limit).query(UUID.class).list();
    }

    public List<String> activeRules() {
        return jdbc.sql("select row_to_json(r)::text from notification_rule r where is_active order by created_at")
                .query(String.class).list();
    }

    /** Tenants with recent API activity (idempotency_key has no RLS); used by the background sweep. */
    public List<UUID> knownTenants() {
        return jdbc.sql("select distinct tenant_id from idempotency_key").query(UUID.class).list();
    }

    private static String table(String t) {
        if (!t.matches("[a-z_]+")) {
            throw new IllegalArgumentException(t);
        }
        return t;
    }
}
