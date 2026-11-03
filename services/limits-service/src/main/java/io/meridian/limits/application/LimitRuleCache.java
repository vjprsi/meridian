package io.meridian.limits.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.limits.domain.LimitModel;
import io.meridian.limits.infrastructure.Db;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Per-tenant snapshot of the ACTIVE, currently valid limit rules (TTL; local writes invalidate immediately). */
@Component
public class LimitRuleCache {
    private record Snapshot(long loadedAt, List<LimitModel.Rule> rules) {}

    private final Db db;
    private final long ttlNanos;
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();

    public LimitRuleCache(Db db, @Value("${meridian.limits.rule-cache-ttl-ms:5000}") long ttlMs) {
        this.db = db;
        this.ttlNanos = ttlMs * 1_000_000L;
    }

    public List<LimitModel.Rule> rules() {
        UUID tenant = TenantContext.require();
        Snapshot s = snapshots.get(tenant);
        if (s == null || System.nanoTime() - s.loadedAt() > ttlNanos) {
            s = new Snapshot(System.nanoTime(), load());
            snapshots.put(tenant, s);
        }
        return s.rules();
    }

    public void invalidate() {
        TenantContext.current().ifPresent(snapshots::remove);
    }

    private List<LimitModel.Rule> load() {
        List<LimitModel.Rule> rules = new java.util.ArrayList<>(db.list("""
                select row_to_json(r)::text from limit_rule r
                where status = 'ACTIVE' and (valid_from is null or valid_from <= now()) and (valid_to is null or valid_to > now())
                order by code""").stream().map(LimitRuleCache::rule).toList());
        // WE-LIMIT-1: expand ACTIVE Transfer Control Profiles into synthetic rules (deterministic ids) in the same cache.
        rules.addAll(TcpCompiler.compile(loadActive("transfer_control_profile"), loadActive("instrument_tcp")));
        return List.copyOf(rules);
    }

    private List<JsonNode> loadActive(String table) {
        return db.list("select row_to_json(r)::text from " + table + " r"
                + " where status = 'ACTIVE' and (valid_from is null or valid_from <= now())"
                + " and (valid_to is null or valid_to > now()) order by code");
    }

    static LimitModel.Rule rule(JsonNode r) {
        return new LimitModel.Rule(UUID.fromString(r.get("id").asString()), r.get("code").asString(),
                r.get("scope").asString(), Db.str(r, "scope_ref"), Db.str(r, "transaction_type"), Db.str(r, "channel"),
                r.get("period").asString(), r.get("measure").asString(), Db.str(r, "currency"), Db.dec(r, "max_amount"),
                Db.integer(r, "max_count"), r.get("breach_action").asString(), r.get("priority").asInt(),
                r.get("is_regulatory").asBoolean());
    }
}
