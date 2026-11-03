package io.meridian.agentnetwork.application;

import io.meridian.agentnetwork.infrastructure.Rows;
import io.meridian.common.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/**
 * WE-HIER-1: resolves the grade (and its category/domain) that applies to an agent, with a short per-tenant cache
 * (~30s). This is what pricing / limits / commission consult; it falls back to the tenant's un-registered grade when
 * a holder has no explicit grade, which {@link GradeService#unregistered()} lazily bootstraps.
 */
@Component
public class GradeResolver {
    private static final long TTL_MS = 30_000L;

    private final Rows rows;
    private final GradeService grades;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public GradeResolver(Rows rows, GradeService grades) {
        this.rows = rows;
        this.grades = grades;
    }

    private record Cached(UUID gradeId, long at) {}

    /** The active grade assigned to an agent, or the tenant's un-registered grade. */
    public ObjectNode resolveByAgent(UUID agentId) {
        UUID gradeId = cachedAgentGrade(agentId);
        if (gradeId != null) {
            Optional<ObjectNode> g = rows.find(GradeService.GRADE, gradeId);
            if (g.isPresent() && "ACTIVE".equals(Rows.str(g.get(), "status"))) {
                return g.get();
            }
        }
        return grades.unregistered();
    }

    /** The tenant's un-registered grade (bootstrapped on first use). */
    public ObjectNode resolveUnregistered() {
        return grades.unregistered();
    }

    private UUID cachedAgentGrade(UUID agentId) {
        String key = TenantContext.current().map(UUID::toString).orElse("-") + ":" + agentId;
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c != null && now - c.at() < TTL_MS) {
            return c.gradeId();
        }
        UUID gradeId = rows.find("agent", agentId).map(a -> Rows.uuid(a, "grade_id")).orElse(null);
        cache.put(key, new Cached(gradeId, now));
        return gradeId;
    }

    /** Drops the cached grade of an agent (called when a grade is assigned or the agent is moved). */
    public void invalidate(UUID agentId) {
        String suffix = ":" + agentId;
        cache.keySet().removeIf(k -> k.endsWith(suffix));
    }
}
