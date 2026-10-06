package io.meridian.fraud.infrastructure;

import java.util.Collection;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The two database round trips of a fraud evaluation. Each is a single SQL statement built from data-modifying
 * CTEs: (1) upsert the device and read replay, velocity, session signals and list hits; (2) insert the score, rule
 * hits, alert and outbox events.
 */
@Component
public class EvaluationStore {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public EvaluationStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Device attributes reported by the SDK for the upsert; fingerprint null = no device. */
    public record DeviceInput(String fingerprint, String platform, String osVersion, String appVersion, String model,
                              boolean emulator, boolean rooted, Boolean attestationPassed, boolean remoteAccess,
                              String ip, String country) {}

    public JsonNode loadFacts(String idempotencyKey, DeviceInput d, UUID customerId, String currency, String sessionId,
                              Collection<String> hashes, Collection<UUID> listIds, String principal) {
        String sql = """
                with replay as (
                  select row_to_json(s)::jsonb as j from fraud_score s where s.idempotency_key = cast(:key as text)
                ),
                dev as (
                  insert into device_fingerprint as d (tenant_id, fingerprint_hash, customer_id, platform, os_version,
                      app_version, device_model, is_emulator, is_rooted, attestation_passed, remote_access_tool,
                      last_ip_address, last_country, created_by, updated_by)
                  select current_setting('app.tenant_id')::uuid, cast(:fp as text), cast(:customer as uuid),
                         coalesce(cast(:platform as text), 'UNKNOWN'), cast(:os as text), cast(:app as text),
                         cast(:model as text), cast(:emulator as boolean), cast(:rooted as boolean),
                         cast(:attested as boolean), cast(:remote as boolean), cast(:ip as inet), cast(:country as text),
                         cast(:principal as text), cast(:principal as text)
                  where cast(:fp as text) is not null
                  on conflict (tenant_id, fingerprint_hash) do update set
                    customers_seen = d.customers_seen + case when excluded.customer_id is not null
                        and d.customer_id is not null and d.customer_id <> excluded.customer_id then 1 else 0 end,
                    customer_id = coalesce(excluded.customer_id, d.customer_id),
                    os_version = coalesce(excluded.os_version, d.os_version),
                    app_version = coalesce(excluded.app_version, d.app_version),
                    device_model = coalesce(excluded.device_model, d.device_model),
                    is_emulator = d.is_emulator or excluded.is_emulator,
                    is_rooted = d.is_rooted or excluded.is_rooted,
                    attestation_passed = coalesce(excluded.attestation_passed, d.attestation_passed),
                    remote_access_tool = excluded.remote_access_tool,
                    last_ip_address = coalesce(excluded.last_ip_address, d.last_ip_address),
                    last_country = coalesce(excluded.last_country, d.last_country),
                    last_seen_at = now(),
                    updated_by = excluded.updated_by
                  returning d.*, (xmax = 0) as is_new
                ),
                hist as (
                  select created_at, amount, currency, device_fingerprint_id from fraud_score
                  where customer_id = cast(:customer as uuid) and created_at > now() - interval '30 days' and amount is not null
                ),
                vel as (
                  select count(*) filter (where created_at > now() - interval '1 hour') as count_1h,
                         count(*) filter (where created_at > now() - interval '24 hours') as count_24h,
                         coalesce(sum(amount) filter (where created_at > now() - interval '24 hours'
                             and currency = cast(:ccy as text)), 0) as amount_24h,
                         count(*) as count_30d,
                         round(avg(amount) filter (where currency = cast(:ccy as text) and amount > 0), 2) as avg_amount_30d,
                         (select count(*) from (
                             select device_fingerprint_id from hist
                             where created_at > now() - interval '24 hours' and device_fingerprint_id is not null
                             union select id from dev) x) as distinct_devices_24h
                  from hist
                ),
                sig as (
                  select bool_or(on_active_call) as on_active_call,
                         bool_or(remote_access_detected) as remote_access_detected,
                         min(typing_cadence_score) as typing_cadence_score,
                         max(navigation_anomaly_score) as navigation_anomaly_score,
                         count(*) as signals
                  from behavioural_signal
                  where session_id = cast(:session as text) and captured_at > now() - interval '2 hours'
                ),
                hits as (
                  select distinct e.fraud_list_id from fraud_list_entry e
                  where e.fraud_list_id = any(cast(:lists as uuid[])) and e.value_hash = any(cast(:hashes as text[]))
                    and (e.expires_at is null or e.expires_at > now())
                )
                select json_build_object(
                  'replay', (select j from replay),
                  'device', (select row_to_json(dev) from dev),
                  'velocity', (select row_to_json(vel) from vel),
                  'session', (select row_to_json(sig) from sig),
                  'list_ids', (select coalesce(json_agg(fraud_list_id), '[]'::json) from hits),
                  'hour_utc', extract(hour from now() at time zone 'UTC')::int
                )::text""";
        String row = jdbc.sql(sql)
                .param("key", idempotencyKey)
                .param("fp", d == null ? null : d.fingerprint())
                .param("customer", customerId)
                .param("platform", d == null ? null : d.platform())
                .param("os", d == null ? null : d.osVersion())
                .param("app", d == null ? null : d.appVersion())
                .param("model", d == null ? null : d.model())
                .param("emulator", d != null && d.emulator())
                .param("rooted", d != null && d.rooted())
                .param("attested", d == null ? null : d.attestationPassed())
                .param("remote", d != null && d.remoteAccess())
                .param("ip", d == null ? null : d.ip())
                .param("country", d == null ? null : d.country())
                .param("principal", principal)
                .param("ccy", currency)
                .param("session", sessionId)
                .param("lists", Db.array(listIds))
                .param("hashes", Db.array(hashes))
                .query(String.class).single();
        return json.readTree(row);
    }

    /**
     * Inserts score, hits and alert plus their outbox events in one statement. Returns the stored score row, or
     * null when a concurrent request with the same idempotency key won (the caller then replays).
     */
    public JsonNode persist(ObjectNode score, ArrayNode hits, ObjectNode alertOrNull, String principal) {
        String headers = json.writeValueAsString(java.util.Map.of("ce_principal", principal));
        String sql = """
                with s as (
                  insert into fraud_score (id, tenant_id, created_by, subject_type, subject_id, customer_id, account_id,
                      card_id, amount, currency, channel, merchant_category_code, counterparty_country,
                      device_fingerprint_id, ip_address, score, model_score, rule_score, model_id, model_version, decision,
                      reason_codes, features, shadow_decision, latency_ms, correlation_id, session_id, fraud_alert_id,
                      idempotency_key)
                  select r.id, current_setting('app.tenant_id')::uuid, :principal, r.subject_type, r.subject_id,
                      r.customer_id, r.account_id, r.card_id, r.amount, r.currency, r.channel, r.merchant_category_code,
                      r.counterparty_country, r.device_fingerprint_id, r.ip_address, r.score, r.model_score, r.rule_score,
                      r.model_id, r.model_version, r.decision, r.reason_codes, r.features, r.shadow_decision, r.latency_ms,
                      r.correlation_id, r.session_id, r.fraud_alert_id, r.idempotency_key
                  from json_populate_record(null::fraud_score, cast(:score as json)) r
                  on conflict (tenant_id, idempotency_key) do nothing
                  returning *
                ),
                h as (
                  insert into fraud_rule_hit (tenant_id, created_by, fraud_score_id, fraud_rule_id, rule_version, action,
                      score_adjustment, shadow, matched_values)
                  select current_setting('app.tenant_id')::uuid, :principal, r.fraud_score_id, r.fraud_rule_id,
                      r.rule_version, r.action, r.score_adjustment, r.shadow, r.matched_values
                  from json_populate_recordset(null::fraud_rule_hit, cast(:hits as json)) r
                  where exists (select 1 from s)
                  returning id
                ),
                a as (
                  insert into fraud_alert (id, tenant_id, created_by, updated_by, fraud_score_id, customer_id, subject_type,
                      subject_id, severity, status, typology, amount, currency, sla_due_at, reason_codes)
                  select r.id, current_setting('app.tenant_id')::uuid, :principal, :principal, r.fraud_score_id,
                      r.customer_id, r.subject_type, r.subject_id, r.severity, 'OPEN', r.typology, r.amount, r.currency,
                      now() + make_interval(mins => cast(:sla as int)), r.reason_codes
                  from json_populate_recordset(null::fraud_alert, cast(:alerts as json)) r
                  where exists (select 1 from s)
                  returning *
                ),
                o as (
                  insert into outbox (tenant_id, aggregate_type, aggregate_id, event_type, payload, headers)
                  select current_setting('app.tenant_id')::uuid, x.agg, x.id, x.type, x.payload, cast(:headers as jsonb)
                  from (
                    select 'fraud_score' as agg, s.id, 'risk.fraud_score.evaluated' as type, to_jsonb(s) as payload from s
                    union all
                    select 'fraud_score', s.id, 'risk.fraud_score.blocked', to_jsonb(s) from s where s.decision = 'BLOCK'
                    union all
                    select 'fraud_alert', a.id, 'risk.fraud_alert.raised', to_jsonb(a) from a
                  ) x
                  returning 1
                )
                select row_to_json(s)::text from s""";
        ArrayNode alerts = json.createArrayNode();
        int slaMinutes = 0;
        if (alertOrNull != null) {
            slaMinutes = alertOrNull.remove("sla_minutes").asInt();
            alerts.add(alertOrNull);
        }
        String row = jdbc.sql(sql)
                .param("principal", principal)
                .param("score", json.writeValueAsString(score))
                .param("hits", json.writeValueAsString(hits))
                .param("alerts", json.writeValueAsString(alerts))
                .param("sla", slaMinutes)
                .param("headers", headers)
                .query(String.class).optional().orElse(null);
        return row == null ? null : json.readTree(row);
    }
}
