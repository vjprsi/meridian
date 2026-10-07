package io.meridian.mover;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

/**
 * Operator view on the management port only ({@code /actuator/moves}, never routed; SEC-31): recent runs, one run with
 * its per-table progress and verification, and {@code POST /actuator/moves/{moveId}} to retry a FAILED run after the
 * cause is fixed (copied pages are skipped, verification runs again).
 */
@Endpoint(id = "moves")
public class MovesEndpoint {
    private final ProgressStore store;
    private final MoveScheduler scheduler;

    public MovesEndpoint(ProgressStore store, MoveScheduler scheduler) {
        this.store = store;
        this.scheduler = scheduler;
    }

    @ReadOperation
    public List<Map<String, Object>> recent() {
        return store.recent(50).stream().map(MovesEndpoint::summary).toList();
    }

    @ReadOperation
    public Map<String, Object> run(@Selector String moveId) {
        UUID id = UUID.fromString(moveId);
        Map<String, Object> out = store.run(id).map(MovesEndpoint::summary).orElse(new LinkedHashMap<>());
        out.put("tables", store.items(id).stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("service", i.service());
            m.put("table", i.table());
            m.put("phase", i.phase());
            m.put("rows_copied", i.rowsCopied());
            m.put("deferred", i.deferred() == null ? 0 : i.deferred().size());
            m.put("source_rows", i.sourceRows());
            m.put("target_rows", i.targetRows());
            m.put("match", i.sourceDigest() != null && i.sourceDigest().equals(i.targetDigest()));
            return m;
        }).toList());
        return out;
    }

    @WriteOperation
    public Map<String, Object> retry(@Selector String moveId) {
        UUID id = UUID.fromString(moveId);
        boolean retried = store.retry(id);
        if (retried) {
            scheduler.submit(id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("move_id", moveId);
        out.put("retried", retried);
        return out;
    }

    private static Map<String, Object> summary(ProgressStore.Run r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("move_id", r.moveId().toString());
        m.put("tenant_id", r.tenantId().toString());
        m.put("from_cell", r.fromCell());
        m.put("to_cell", r.toCell());
        m.put("strategy", r.strategy());
        m.put("status", r.status());
        m.put("attempts", r.attempts());
        m.put("documents_copied", r.docsCopied());
        m.put("documents_verified", r.docsVerified());
        m.put("checkpoint", r.checkpoint());
        m.put("last_error", r.lastError());
        return m;
    }
}
