package io.meridian.cardauthorization.application;

import io.meridian.cardauthorization.config.AuthProperties;
import io.meridian.cardauthorization.domain.AuthRule;
import io.meridian.cardauthorization.domain.Expression;
import io.meridian.cardauthorization.infrastructure.AuthStore;
import io.meridian.common.tenancy.TenantContext;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Compiled authorisation rules per tenant, cached for a short TTL so the hot path does not query or parse them on
 * every message. Rule create/update/delete invalidate the tenant's entry immediately.
 */
@Component
public class RuleCache {
    private static final Logger log = LoggerFactory.getLogger(RuleCache.class);

    private record Entry(long loadedAt, List<AuthRule> rules) {}

    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();
    private final AuthStore store;
    private final AuthProperties props;

    public RuleCache(AuthStore store, AuthProperties props) {
        this.store = store;
        this.props = props;
    }

    /** Active rules of the program plus tenant-wide rules, sorted by priority (validity checked by the engine). */
    public List<AuthRule> forProgram(UUID programId) {
        UUID tenant = TenantContext.require();
        Entry e = cache.get(tenant);
        long now = System.currentTimeMillis();
        if (e == null || now - e.loadedAt() > props.ruleCacheTtlMs()) {
            e = new Entry(now, load());
            cache.put(tenant, e);
        }
        List<AuthRule> out = new ArrayList<>();
        for (AuthRule r : e.rules()) {
            if (r.programId() == null || r.programId().equals(programId)) {
                out.add(r);
            }
        }
        return out;
    }

    public void invalidate() {
        cache.remove(TenantContext.require());
    }

    private List<AuthRule> load() {
        List<AuthRule> rules = new ArrayList<>();
        for (JsonNode n : store.query("select row_to_json(r)::text from card_authorization_rule r where is_active order by priority, id")) {
            try {
                rules.add(compile(n));
            } catch (IllegalArgumentException ex) {
                log.warn("authorisation rule {} skipped: {}", n.get("id").asString(), ex.getMessage());
            }
        }
        rules.sort(Comparator.comparingInt(AuthRule::priority));
        return List.copyOf(rules);
    }

    public static AuthRule compile(JsonNode n) {
        return new AuthRule(uuid(n, "id"), uuid(n, "card_program_id"), text(n, "name"),
                n.path("priority").asInt(100), Expression.parse(text(n, "condition_expression")), text(n, "action"),
                text(n, "decline_reason"), !n.has("is_active") || n.get("is_active").asBoolean(true),
                ts(n, "valid_from"), ts(n, "valid_to"));
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static UUID uuid(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : UUID.fromString(s);
    }

    private static OffsetDateTime ts(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : OffsetDateTime.parse(s);
    }
}
