package io.meridian.transactionmonitoring.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.transactionmonitoring.domain.Scenarios;
import io.meridian.transactionmonitoring.infrastructure.Db;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Active scenarios with their versions in force, cached per tenant (TTL; local changes invalidate). */
@Component
public class ScenarioCatalog {
    public record Active(UUID scenarioId, String code, String type, String runMode, String segment, UUID versionId,
                         int version, Scenarios.Params params) {}

    private record Snapshot(long loadedAt, List<Active> scenarios) {}

    private final Db db;
    private final JsonMapper json;
    private final long ttlNanos;
    private final Map<UUID, Snapshot> cache = new ConcurrentHashMap<>();

    public ScenarioCatalog(Db db, JsonMapper json, @Value("${meridian.tm.scenario-cache-ttl-ms:5000}") long ttlMs) {
        this.db = db;
        this.json = json;
        this.ttlNanos = ttlMs * 1_000_000L;
    }

    public List<Active> active() {
        UUID tenant = TenantContext.require();
        Snapshot s = cache.get(tenant);
        if (s == null || System.nanoTime() - s.loadedAt() > ttlNanos) {
            List<Active> list = db.list("""
                    select json_build_object('s', row_to_json(s), 'v', row_to_json(v))::text
                    from tm_scenario s join tm_scenario_version v on v.tm_scenario_id = s.id and v.scenario_version = s.active_version
                    where s.status = 'ACTIVE' order by s.code""").stream().map(n -> active(n.get("s"), n.get("v"))).toList();
            s = new Snapshot(System.nanoTime(), list);
            cache.put(tenant, s);
        }
        return s.scenarios();
    }

    public void invalidate() {
        TenantContext.current().ifPresent(cache::remove);
    }

    Active active(JsonNode s, JsonNode v) {
        return new Active(UUID.fromString(s.get("id").asString()), s.get("code").asString(), s.get("scenario_type").asString(),
                s.get("run_mode").asString(), Db.str(s, "customer_segment"), UUID.fromString(v.get("id").asString()),
                v.get("scenario_version").asInt(), params(v));
    }

    @SuppressWarnings("unchecked")
    Scenarios.Params params(JsonNode v) {
        Map<String, Object> values = v.hasNonNull("parameters") && v.get("parameters").isObject()
                ? json.convertValue(v.get("parameters"), LinkedHashMap.class) : Map.of();
        BigDecimal weight = Db.dec(v, "risk_weight");
        return new Scenarios.Params(v.get("lookback_days").asInt(), Db.dec(v, "min_amount"), Db.integer(v, "min_count"),
                weight == null ? BigDecimal.ONE : weight, values);
    }
}
