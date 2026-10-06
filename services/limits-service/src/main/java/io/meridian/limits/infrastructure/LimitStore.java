package io.meridian.limits.infrastructure;

import java.util.Collection;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * SQL of the limit check hot path. Usage counters are locked with INSERT ... ON CONFLICT DO UPDATE in a fixed
 * (rule, subject) order, which both creates missing period counters and row-locks them in one statement without
 * deadlocks; rolling-window history and active overrides are read in the same statement.
 */
@Component
public class LimitStore {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public LimitStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @param keys     current-period counters: limit_rule_id, subject_type, subject_id, period_start, period_end, currency
     * @param rolling  rolling windows: limit_rule_id, subject_id, window_start, bucket_start (history before the bucket)
     * @param lock     true: create and lock the counters (reservation); false: read only (dry run)
     */
    public JsonNode load(ArrayNode keys, ArrayNode rolling, Collection<UUID> subjects, Collection<UUID> ruleIds, boolean lock,
                         String principal) {
        String usage = lock ? """
                up as (
                  insert into limit_usage as u (tenant_id, created_by, updated_by, limit_rule_id, subject_type, subject_id,
                      period_start, period_end, currency)
                  select current_setting('app.tenant_id')::uuid, cast(:principal as text), cast(:principal as text),
                      k.limit_rule_id, k.subject_type, k.subject_id, k.period_start, k.period_end, k.currency
                  from input k order by k.limit_rule_id, k.subject_id
                  on conflict (limit_rule_id, subject_id, period_start) do update set period_end = excluded.period_end
                  returning u.*
                ),""" : """
                up as (
                  select u.* from limit_usage u join input k on u.limit_rule_id = k.limit_rule_id
                    and u.subject_id = k.subject_id and u.period_start = k.period_start
                ),""";
        String sql = """
                with input as (
                  select * from json_to_recordset(cast(:keys as json)) as k(limit_rule_id uuid, subject_type text,
                      subject_id uuid, period_start timestamptz, period_end timestamptz, currency text)
                ),
                """ + usage + """
                prior as (
                  select r.limit_rule_id, r.subject_id,
                         coalesce(sum(u.used_amount), 0) as used_amount, coalesce(sum(u.reserved_amount), 0) as reserved_amount,
                         coalesce(sum(u.used_count), 0) as used_count, coalesce(sum(u.reserved_count), 0) as reserved_count
                  from json_to_recordset(cast(:rolling as json)) as r(limit_rule_id uuid, subject_id uuid,
                       window_start timestamptz, bucket_start timestamptz)
                  left join limit_usage u on u.limit_rule_id = r.limit_rule_id and u.subject_id = r.subject_id
                       and u.period_start >= r.window_start and u.period_start < r.bucket_start
                  group by r.limit_rule_id, r.subject_id
                ),
                ov as (
                  select o.* from limit_override o
                  where o.status = 'ACTIVE' and o.subject_id = any(cast(:subjects as uuid[]))
                    and o.limit_rule_id = any(cast(:rules as uuid[])) and now() >= o.valid_from and now() < o.valid_to
                )
                select json_build_object(
                  'usage', coalesce((select json_agg(row_to_json(up)) from up), '[]'::json),
                  'prior', coalesce((select json_agg(row_to_json(prior)) from prior), '[]'::json),
                  'overrides', coalesce((select json_agg(row_to_json(ov)) from ov), '[]'::json)
                )::text""";
        String row = jdbc.sql(sql)
                .param("keys", json.writeValueAsString(keys))
                .param("rolling", json.writeValueAsString(rolling))
                .param("subjects", Db.array(subjects))
                .param("rules", Db.array(ruleIds))
                .param("principal", principal)
                .query(String.class).single();
        return json.readTree(row);
    }

    /**
     * Inserts the check, applies the reservation deltas and writes the breach event in one statement. Returns null if
     * a concurrent request with the same idempotency key won (nothing is applied then).
     */
    public JsonNode persist(ObjectNode check, ArrayNode deltas, boolean breached, String principal) {
        String headers = json.writeValueAsString(java.util.Map.of("ce_principal", principal));
        String sql = """
                with c as (
                  insert into limit_check (id, tenant_id, created_by, updated_by, subject_type, subject_id, customer_id,
                      kyc_tier, customer_segment, product_id, transaction_type, channel, amount, currency, current_balance,
                      allowed, status, action, evaluated_rule_ids, breached_rule_ids, remaining, reservations,
                      idempotency_key, correlation_id, expires_at, latency_ms)
                  select r.id, current_setting('app.tenant_id')::uuid, cast(:principal as text), cast(:principal as text),
                      r.subject_type, r.subject_id, r.customer_id, r.kyc_tier, r.customer_segment, r.product_id,
                      r.transaction_type, r.channel, r.amount, r.currency, r.current_balance, r.allowed, r.status, r.action,
                      r.evaluated_rule_ids, r.breached_rule_ids, r.remaining, r.reservations, r.idempotency_key,
                      r.correlation_id, coalesce(r.expires_at, now() + interval '15 minutes'), r.latency_ms
                  from json_populate_record(null::limit_check, cast(:check as json)) r
                  on conflict (tenant_id, idempotency_key) do nothing
                  returning *
                ),
                d as (
                  select * from json_to_recordset(cast(:deltas as json)) as d(limit_usage_id uuid, amount numeric, count int)
                ),
                upd as (
                  update limit_usage u set reserved_amount = u.reserved_amount + d.amount,
                      reserved_count = u.reserved_count + d.count
                  from d where u.id = d.limit_usage_id and exists (select 1 from c)
                  returning u.id
                ),
                o as (
                  insert into outbox (tenant_id, aggregate_type, aggregate_id, event_type, payload, headers)
                  select current_setting('app.tenant_id')::uuid, 'limit_check', c.id, 'risk.limit.breached',
                         to_jsonb(c) - 'reservations', cast(:headers as jsonb)
                  from c where cast(:breached as boolean)
                  returning 1
                )
                select row_to_json(c)::text from c""";
        String row = jdbc.sql(sql)
                .param("check", json.writeValueAsString(check))
                .param("deltas", json.writeValueAsString(deltas))
                .param("breached", breached)
                .param("principal", principal)
                .param("headers", headers)
                .query(String.class).optional().orElse(null);
        return row == null ? null : json.readTree(row);
    }

    /**
     * Settles reservations: each delta {limit_usage_id, amount, count, commit_amount, commit_count} releases the
     * reserved figures and adds the committed ones to usage. Rows are locked in id order first.
     */
    public void settle(ArrayNode deltas) {
        if (deltas.isEmpty()) {
            return;
        }
        String d = json.writeValueAsString(deltas);
        jdbc.sql("""
                select u.id from limit_usage u
                where u.id in (select limit_usage_id from json_to_recordset(cast(:d as json)) as d(limit_usage_id uuid))
                order by u.id for update""").param("d", d).query(String.class).list();
        jdbc.sql("""
                update limit_usage u set
                    reserved_amount = greatest(u.reserved_amount - d.amount, 0),
                    reserved_count = greatest(u.reserved_count - d.count, 0),
                    used_amount = u.used_amount + d.commit_amount,
                    used_count = u.used_count + d.commit_count
                from json_to_recordset(cast(:d as json)) as d(limit_usage_id uuid, amount numeric, count int,
                     commit_amount numeric, commit_count int)
                where u.id = d.limit_usage_id""").param("d", d).update();
    }
}
