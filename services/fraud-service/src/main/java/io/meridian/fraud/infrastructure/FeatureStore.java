package io.meridian.fraud.infrastructure;

import io.meridian.common.time.Clocks;
import io.meridian.fraud.domain.features.Buckets;
import io.meridian.fraud.domain.features.FeatureEvent;
import io.meridian.fraud.domain.features.Profile;
import io.meridian.fraud.domain.features.StoreFeatures;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * PostgreSQL side of the real-time feature store (tenant-scoped by row-level security). Applying an event is one
 * statement: the CloudEvent id is inserted first and every counter / profile / link change is conditional on that
 * insert, so a redelivered event changes nothing. Reading the features of an evaluation is one statement too.
 * Storage is bounded by retention: 5-minute buckets 2 h, hourly buckets 8 d, event ids 8 d, profiles and links
 * 400 days after they were last seen ({@link #prune}).
 */
@Component
public class FeatureStore {
    public static final Duration EVENT_RETENTION = Duration.ofDays(8);
    public static final Duration PROFILE_RETENTION = Duration.ofDays(400);
    static final Duration FUTURE_SKEW = Duration.ofMinutes(5);
    static final int PRUNE_BATCH = 5000;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public FeatureStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Applies an event once; returns false when the event id was already applied (or is older than the retention). */
    public boolean apply(FeatureEvent e, String principal) {
        Instant now = Clocks.now().toInstant();
        Instant at = e.occurredAt().isAfter(now.plus(FUTURE_SKEW)) ? now : e.occurredAt();
        boolean late = at.isBefore(now.minus(EVENT_RETENTION));
        ArrayNode counters = json.createArrayNode();
        ArrayNode amountProfiles = json.createArrayNode();
        ArrayNode plainProfiles = json.createArrayNode();
        ArrayNode links = json.createArrayNode();
        BigDecimal amount = e.amount();
        if (!late) {
            for (Map.Entry<String, String> c : e.counters().entrySet()) {
                if (!at.isBefore(now.minus(Buckets.M5_RETENTION))) {
                    counters.add(counter(c.getKey(), c.getValue(), "M5", Buckets.floor(at, Buckets.M5), e.currency(), amount));
                }
                counters.add(counter(c.getKey(), c.getValue(), "H1", Buckets.floor(at, Buckets.H1), e.currency(), amount));
            }
        }
        double theta = Profile.angle(at);
        for (Map.Entry<String, String> p : e.profiles().entrySet()) {
            boolean withAmount = "CUSTOMER".equals(p.getKey()) && amount != null;
            ObjectNode n = (withAmount ? amountProfiles : plainProfiles).addObject();
            n.put("entity_type", p.getKey());
            n.put("entity_key", p.getValue());
            n.put("currency", withAmount ? e.currency() : FeatureEvent.NO_CURRENCY);
            n.put("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC).toString());
            if (withAmount) {
                n.put("x", amount.toPlainString());
            }
            n.put("s", Math.sin(theta));
            n.put("c", Math.cos(theta));
        }
        for (FeatureEvent.Link l : e.links()) {
            ObjectNode n = links.addObject();
            n.put("link_type", l.type());
            n.put("from_key", l.from());
            n.put("to_key", l.to());
            n.put("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC).toString());
        }
        String sql = """
                with ev as (
                  insert into fraud_feature_event (tenant_id, created_by, updated_by, event_id, event_type, occurred_at, applied)
                  values (current_setting('app.tenant_id')::uuid, :by, :by, :id, :type, :at, :applied)
                  on conflict (tenant_id, event_id) do nothing
                  returning 1
                ),
                c as (
                  insert into fraud_feature_counter as f (tenant_id, created_by, updated_by, entity_type, entity_key,
                      granularity, bucket_start, currency, event_count, amount_sum)
                  select current_setting('app.tenant_id')::uuid, :by, :by, r.entity_type, r.entity_key, r.granularity,
                         r.bucket_start, r.currency, r.event_count, r.amount_sum
                  from json_populate_recordset(null::fraud_feature_counter, cast(:counters as json)) r
                  where exists (select 1 from ev)
                  on conflict (tenant_id, entity_type, entity_key, granularity, bucket_start, currency) do update
                    set event_count = f.event_count + excluded.event_count, amount_sum = f.amount_sum + excluded.amount_sum
                  returning 1
                ),
                pa as (
                  insert into fraud_feature_profile as f (tenant_id, created_by, updated_by, entity_type, entity_key, currency,
                      first_seen_at, last_seen_at, event_count, amount_mean, amount_var, hour_sin, hour_cos)
                  select current_setting('app.tenant_id')::uuid, :by, :by, r.entity_type, r.entity_key, r.currency, r.at, r.at,
                         1, r.x, 0, r.s, r.c
                  from json_to_recordset(cast(:amount_profiles as json))
                       as r(entity_type text, entity_key text, currency text, at timestamptz, x numeric, s numeric, c numeric)
                  where exists (select 1 from ev)
                  on conflict (tenant_id, entity_type, entity_key, currency) do update set
                    event_count = f.event_count + 1,
                    first_seen_at = least(f.first_seen_at, excluded.first_seen_at),
                    last_seen_at = greatest(f.last_seen_at, excluded.last_seen_at),
                    amount_mean = f.amount_mean + greatest(1.0 / (f.event_count + 1), :alpha) * (excluded.amount_mean - f.amount_mean),
                    amount_var = least((1 - greatest(1.0 / (f.event_count + 1), :alpha))
                        * (f.amount_var + greatest(1.0 / (f.event_count + 1), :alpha) * (excluded.amount_mean - f.amount_mean) ^ 2), 1e19),
                    hour_sin = f.hour_sin + greatest(1.0 / (f.event_count + 1), :alpha) * (excluded.hour_sin - f.hour_sin),
                    hour_cos = f.hour_cos + greatest(1.0 / (f.event_count + 1), :alpha) * (excluded.hour_cos - f.hour_cos)
                  returning 1
                ),
                pp as (
                  insert into fraud_feature_profile as f (tenant_id, created_by, updated_by, entity_type, entity_key, currency,
                      first_seen_at, last_seen_at, event_count, hour_sin, hour_cos)
                  select current_setting('app.tenant_id')::uuid, :by, :by, r.entity_type, r.entity_key, r.currency, r.at, r.at,
                         1, r.s, r.c
                  from json_to_recordset(cast(:plain_profiles as json))
                       as r(entity_type text, entity_key text, currency text, at timestamptz, s numeric, c numeric)
                  where exists (select 1 from ev)
                  on conflict (tenant_id, entity_type, entity_key, currency) do update set
                    event_count = f.event_count + 1,
                    first_seen_at = least(f.first_seen_at, excluded.first_seen_at),
                    last_seen_at = greatest(f.last_seen_at, excluded.last_seen_at),
                    hour_sin = f.hour_sin + greatest(1.0 / (f.event_count + 1), :alpha) * (excluded.hour_sin - f.hour_sin),
                    hour_cos = f.hour_cos + greatest(1.0 / (f.event_count + 1), :alpha) * (excluded.hour_cos - f.hour_cos)
                  returning 1
                ),
                l as (
                  insert into fraud_feature_link as f (tenant_id, created_by, updated_by, link_type, from_key, to_key,
                      first_seen_at, last_seen_at, event_count)
                  select current_setting('app.tenant_id')::uuid, :by, :by, r.link_type, r.from_key, r.to_key, r.at, r.at, 1
                  from json_to_recordset(cast(:links as json)) as r(link_type text, from_key text, to_key text, at timestamptz)
                  where exists (select 1 from ev)
                  on conflict (tenant_id, link_type, from_key, to_key) do update set
                    event_count = f.event_count + 1,
                    first_seen_at = least(f.first_seen_at, excluded.first_seen_at),
                    last_seen_at = greatest(f.last_seen_at, excluded.last_seen_at)
                  returning 1
                )
                select (select count(*) from ev) + 0 * ((select count(*) from c) + (select count(*) from pa)
                       + (select count(*) from pp) + (select count(*) from l))""";
        Integer inserted = jdbc.sql(sql)
                .param("by", principal)
                .param("id", e.eventId())
                .param("type", e.type())
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("applied", !late)
                .param("counters", json.writeValueAsString(counters))
                .param("amount_profiles", json.writeValueAsString(amountProfiles))
                .param("plain_profiles", json.writeValueAsString(plainProfiles))
                .param("links", json.writeValueAsString(links))
                .param("alpha", BigDecimal.valueOf(Profile.ALPHA_MIN))
                .query(Integer.class).single();
        return inserted != null && inserted > 0 && !late;
    }

    private ObjectNode counter(String type, String key, String granularity, Instant bucket, String currency, BigDecimal amount) {
        ObjectNode n = json.createObjectNode();
        n.put("entity_type", type);
        n.put("entity_key", key);
        n.put("granularity", granularity);
        n.put("bucket_start", OffsetDateTime.ofInstant(bucket, ZoneOffset.UTC).toString());
        n.put("currency", currency);
        n.put("event_count", 1);
        n.put("amount_sum", amount == null ? "0" : amount.toPlainString());
        return n;
    }

    /** Entities of an evaluation request (null = not named). */
    public record Keys(String customer, String card, String account, String deviceFingerprint, String merchant,
                       String beneficiary, String country, String currency) {}

    /** Reads everything the features of one evaluation need, in one statement. */
    public StoreFeatures.Snapshot lookup(Keys k, Double amount, Instant now) {
        Map<String, String> keys = new LinkedHashMap<>();
        put(keys, "CUSTOMER", k.customer());
        put(keys, "CARD", k.card());
        put(keys, "ACCOUNT", k.account());
        put(keys, "MERCHANT", k.merchant());
        put(keys, "BENEFICIARY", k.beneficiary());
        put(keys, "CUSTOMER_LOGIN", k.customer());
        ArrayNode keyRows = json.createArrayNode();
        keys.forEach((t, v) -> keyRows.addObject().put("entity_type", t).put("entity_key", v));
        // links to check: type -> (from, to); "$DEVICE" is resolved from the fingerprint in SQL
        Map<String, String[]> pairs = new LinkedHashMap<>();
        pair(pairs, "CUSTOMER_BENEFICIARY", k.customer(), k.beneficiary());
        pair(pairs, "ACCOUNT_BENEFICIARY", k.account(), k.beneficiary());
        pair(pairs, "CUSTOMER_DEVICE", k.customer(), k.deviceFingerprint() == null ? null : "$DEVICE");
        pair(pairs, "CUSTOMER_COUNTRY", k.customer(), k.country());
        pair(pairs, "CARD_COUNTRY", k.card(), k.country());
        pair(pairs, "CARD_MERCHANT", k.card(), k.merchant());
        pair(pairs, "CUSTOMER_MERCHANT", k.customer(), k.merchant());
        ArrayNode linkRows = json.createArrayNode();
        pairs.forEach((t, p) -> linkRows.addObject().put("link_type", t).put("from_key", p[0]).put("to_key", p[1]));
        String ccy = k.currency() == null ? FeatureEvent.NO_CURRENCY : k.currency();
        String sql = """
                with k as (
                  select x.entity_type, x.entity_key from json_to_recordset(cast(:keys as json)) as x(entity_type text, entity_key text)
                  union all
                  select 'DEVICE', d.id::text from device_fingerprint d where d.fingerprint_hash = cast(:fp as text)
                ),
                c as (
                  select f.entity_type,
                    coalesce(sum(f.event_count) filter (where f.granularity = 'M5' and f.bucket_start >= :w1h), 0) as c1h,
                    coalesce(sum(f.amount_sum) filter (where f.granularity = 'M5' and f.bucket_start >= :w1h and f.currency = :ccy), 0) as a1h,
                    coalesce(sum(f.event_count) filter (where f.granularity = 'H1' and f.bucket_start >= :w24h), 0) as c24h,
                    coalesce(sum(f.amount_sum) filter (where f.granularity = 'H1' and f.bucket_start >= :w24h and f.currency = :ccy), 0) as a24h,
                    coalesce(sum(f.event_count) filter (where f.granularity = 'H1' and f.bucket_start >= :w7d), 0) as c7d,
                    coalesce(sum(f.amount_sum) filter (where f.granularity = 'H1' and f.bucket_start >= :w7d and f.currency = :ccy), 0) as a7d
                  from fraud_feature_counter f join k on f.entity_type = k.entity_type and f.entity_key = k.entity_key
                  where f.bucket_start <= :now
                    and ((f.granularity = 'M5' and f.bucket_start >= :w1h) or (f.granularity = 'H1' and f.bucket_start >= :w7d))
                  group by f.entity_type
                ),
                p as (
                  select p.entity_type, p.currency, p.event_count, p.amount_mean, p.amount_var, p.hour_sin, p.hour_cos,
                         p.first_seen_at, p.last_seen_at, p.fraud_count, p.label_count
                  from fraud_feature_profile p join k on p.entity_type = k.entity_type and p.entity_key = k.entity_key
                  where p.entity_type in ('CUSTOMER', 'MERCHANT', 'BENEFICIARY')
                    and p.currency = case when p.entity_type = 'CUSTOMER' then cast(:ccy as text) else 'XXX' end
                ),
                l as (
                  select x.link_type from json_to_recordset(cast(:links as json)) as x(link_type text, from_key text, to_key text)
                  join fraud_feature_link f on f.link_type = x.link_type and f.from_key = x.from_key
                   and f.to_key = case when x.to_key = '$DEVICE' then (select entity_key from k where entity_type = 'DEVICE') else x.to_key end
                )
                select json_build_object(
                  'device_id', (select entity_key from k where entity_type = 'DEVICE'),
                  'counters', (select coalesce(json_agg(row_to_json(c)), '[]'::json) from c),
                  'profiles', (select coalesce(json_agg(row_to_json(p)), '[]'::json) from p),
                  'customer_first_seen', (select min(first_seen_at) from fraud_feature_profile
                                          where entity_type = 'CUSTOMER' and entity_key = cast(:customer as text)),
                  'links', (select coalesce(json_agg(link_type), '[]'::json) from l)
                )::text""";
        OffsetDateTime nowTs = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        String row = jdbc.sql(sql)
                .param("keys", json.writeValueAsString(keyRows))
                .param("fp", k.deviceFingerprint())
                .param("ccy", ccy)
                .param("now", nowTs)
                .param("w1h", OffsetDateTime.ofInstant(Buckets.Window.H1_WINDOW.start(now), ZoneOffset.UTC))
                .param("w24h", OffsetDateTime.ofInstant(Buckets.Window.H24_WINDOW.start(now), ZoneOffset.UTC))
                .param("w7d", OffsetDateTime.ofInstant(Buckets.Window.D7_WINDOW.start(now), ZoneOffset.UTC))
                .param("links", json.writeValueAsString(linkRows))
                .param("customer", k.customer())
                .query(String.class).single();
        JsonNode r = json.readTree(row);
        Map<String, String> present = new LinkedHashMap<>(keys);
        if (k.deviceFingerprint() != null) {
            present.put("DEVICE", r.hasNonNull("device_id") ? r.get("device_id").asString() : "$new");
        }
        Map<String, double[]> windows = new HashMap<>();
        for (JsonNode c : r.get("counters")) {
            String t = c.get("entity_type").asString();
            windows.put(t + ":1h", new double[] {c.get("c1h").asDouble(), c.get("a1h").asDouble()});
            windows.put(t + ":24h", new double[] {c.get("c24h").asDouble(), c.get("a24h").asDouble()});
            windows.put(t + ":7d", new double[] {c.get("c7d").asDouble(), c.get("a7d").asDouble()});
        }
        Profile customer = null;
        Profile merchant = null;
        Profile beneficiary = null;
        for (JsonNode p : r.get("profiles")) {
            Profile pr = new Profile(p.get("event_count").asLong(), p.get("amount_mean").asDouble(), p.get("amount_var").asDouble(),
                    p.get("hour_sin").asDouble(), p.get("hour_cos").asDouble(), ts(p, "first_seen_at"), ts(p, "last_seen_at"),
                    p.get("fraud_count").asInt(), p.get("label_count").asInt());
            switch (p.get("entity_type").asString()) {
                case "CUSTOMER" -> customer = pr;
                case "MERCHANT" -> merchant = pr;
                default -> beneficiary = pr;
            }
        }
        Set<String> found = new HashSet<>();
        r.get("links").forEach(n -> found.add(n.asString()));
        Map<String, Boolean> links = new LinkedHashMap<>();
        pairs.keySet().forEach(t -> links.put(t, found.contains(t)));
        return new StoreFeatures.Snapshot(now, amount, present, windows, customer, ts(r, "customer_first_seen"), merchant,
                beneficiary, links);
    }

    /** Counts a confirmed outcome label on the merchant profile (merchant risk). */
    public void label(String merchant, boolean fraud, String principal) {
        if (merchant == null || merchant.isBlank() || merchant.length() > 128) {
            return;
        }
        jdbc.sql("""
                insert into fraud_feature_profile as f (tenant_id, created_by, updated_by, entity_type, entity_key, currency,
                    first_seen_at, last_seen_at, event_count, fraud_count, label_count)
                values (current_setting('app.tenant_id')::uuid, :by, :by, 'MERCHANT', :key, 'XXX', now(), now(), 0, :fraud, 1)
                on conflict (tenant_id, entity_type, entity_key, currency) do update set
                  fraud_count = f.fraud_count + excluded.fraud_count, label_count = f.label_count + 1, updated_by = excluded.updated_by""")
                .param("by", principal).param("key", merchant).param("fraud", fraud ? 1 : 0).update();
    }

    /** Deletes expired rows of the current tenant (bounded batches); returns the number deleted. */
    public int prune(Instant now) {
        OffsetDateTime m5 = OffsetDateTime.ofInstant(now.minus(Buckets.M5_RETENTION), ZoneOffset.UTC);
        OffsetDateTime h1 = OffsetDateTime.ofInstant(now.minus(Buckets.H1_RETENTION), ZoneOffset.UTC);
        OffsetDateTime events = OffsetDateTime.ofInstant(now.minus(EVENT_RETENTION), ZoneOffset.UTC);
        OffsetDateTime profiles = OffsetDateTime.ofInstant(now.minus(PROFILE_RETENTION), ZoneOffset.UTC);
        int n = 0;
        n += jdbc.sql("""
                delete from fraud_feature_counter where id in (select id from fraud_feature_counter
                  where (granularity = 'M5' and bucket_start < :m5) or (granularity = 'H1' and bucket_start < :h1) limit :batch)""")
                .param("m5", m5).param("h1", h1).param("batch", PRUNE_BATCH).update();
        n += jdbc.sql("delete from fraud_feature_event where id in (select id from fraud_feature_event where created_at < :t limit :batch)")
                .param("t", events).param("batch", PRUNE_BATCH).update();
        n += jdbc.sql("delete from fraud_feature_profile where id in (select id from fraud_feature_profile where last_seen_at < :t limit :batch)")
                .param("t", profiles).param("batch", PRUNE_BATCH).update();
        n += jdbc.sql("delete from fraud_feature_link where id in (select id from fraud_feature_link where last_seen_at < :t limit :batch)")
                .param("t", profiles).param("batch", PRUNE_BATCH).update();
        return n;
    }

    /** Row counts of the current tenant's feature store (runtime endpoint). */
    public Map<String, Long> sizes() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String t : List.of("fraud_feature_event", "fraud_feature_counter", "fraud_feature_profile", "fraud_feature_link")) {
            m.put(t, jdbc.sql("select count(*) from " + t).query(Long.class).single());
        }
        return m;
    }

    private static Instant ts(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) ? OffsetDateTime.parse(n.get(f).asString()).toInstant() : null;
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank() && v.length() <= 128) {
            m.put(k, v);
        }
    }

    private static void pair(Map<String, String[]> m, String type, String from, String to) {
        if (from != null && to != null && !from.isBlank() && !to.isBlank()) {
            m.put(type, new String[] {from, to});
        }
    }
}
