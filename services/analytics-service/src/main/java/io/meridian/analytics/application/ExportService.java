package io.meridian.analytics.application;

import io.meridian.analytics.domain.CsvWriter;
import io.meridian.analytics.infrastructure.Store;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Asynchronous exports of saved-query results: the job is created QUEUED and processed on a virtual thread (with the
 * requester's tenant bound) into a CSV or JSON file kept for the retention window. A job claims QUEUED -> RUNNING
 * atomically, so a cancelled job is never processed and a job cancelled while running keeps CANCELLED.
 */
@Service
public class ExportService {
    private static final Logger log = LoggerFactory.getLogger(ExportService.class);
    static final Set<String> SUPPORTED = Set.of("CSV", "JSON");
    static final int MAX_EXPORT_ROWS = 10_000;

    private final Store store;
    private final CrudEngine crud;
    private final QueryService queries;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final ExecutorService executor;
    private final boolean async;
    private final int retentionDays;

    public ExportService(Store store, CrudEngine crud, QueryService queries, OutboxWriter outbox, JsonMapper json,
                         TransactionTemplate tx,
                         @Value("${meridian.analytics.exports.async:true}") boolean async,
                         @Value("${meridian.analytics.exports.retention-days:7}") int retentionDays) {
        this.store = store;
        this.crud = crud;
        this.queries = queries;
        this.outbox = outbox;
        this.json = json;
        this.tx = tx;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.async = async;
        this.retentionDays = retentionDays;
    }

    @PreDestroy
    void shutdown() {
        executor.close();
    }

    /** Creates a QUEUED export job and schedules its processing. */
    public String create(UUID queryId, String format, JsonNode parameters) {
        if (format == null || !SUPPORTED.contains(format)) {
            throw Fields.invalid("format", "unsupported_export_format", "Exports are produced as CSV or JSON; " + format + " is not supported");
        }
        JsonNode saved = queries.visible(queryId);
        queries.resolve(saved.get("parameters"), parameters); // fail fast on bad parameters
        UUID id = Ids.newId();
        ObjectNode v = json.createObjectNode();
        v.put("id", id.toString());
        v.put("analytics_saved_query_id", queryId.toString());
        v.put("format", format);
        if (parameters != null && parameters.isObject()) {
            v.set("parameters", parameters);
        }
        v.put("status", "QUEUED");
        v.put("requested_by", TenantContext.principal());
        String row = crud.insert("analytics_export_job", v);
        schedule(id);
        return row;
    }

    void schedule(UUID id) {
        UUID tenant = TenantContext.require();
        String principal = TenantContext.principal();
        Runnable job = () -> {
            try {
                TenantContext.callAs(tenant, principal, () -> {
                    process(id);
                    return null;
                });
            } catch (Exception e) {
                log.error("export {} failed unexpectedly", id, e);
            }
        };
        if (async) {
            executor.execute(job);
        } else {
            job.run();
        }
    }

    /** Processes one job if it is still QUEUED. */
    public void process(UUID id) {
        boolean claimed = store.update("""
                update analytics_export_job set status = 'RUNNING', started_at = now(), updated_by = ?
                where id = ? and status = 'QUEUED'""", TenantContext.principal(), id) == 1;
        if (!claimed) {
            return;
        }
        JsonNode job = store.require("analytics_export_job", id);
        try {
            JsonNode saved = store.require("analytics_saved_query", UUID.fromString(job.get("analytics_saved_query_id").asString()));
            ObjectNode result = queries.execute(saved, job.get("parameters"), MAX_EXPORT_ROWS, null);
            List<String> columns = new ArrayList<>();
            result.get("columns").forEach(c -> columns.add(c.asString()));
            String content = "CSV".equals(job.get("format").asString())
                    ? CsvWriter.write(columns, result.get("rows"))
                    : json.writeValueAsString(result.get("rows"));
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            tx.executeWithoutResult(s -> store.updateReturning("""
                    update analytics_export_job t set status = 'SUCCEEDED', content = ?, row_count = ?, size_bytes = ?, sha256 = ?,
                           finished_at = now(), expires_at = now() + make_interval(days => ?), updated_by = ?
                    where t.id = ? and t.status = 'RUNNING' returning row_to_json(t)::text""",
                    content, result.get("row_count").asLong(), (long) bytes.length, sha256(bytes), retentionDays,
                    TenantContext.principal(), id)
                    .ifPresent(row -> outbox.publish("reporting.analytics_export.completed", "analytics_export_job", id, strip(row))));
        } catch (RuntimeException e) {
            String msg = e instanceof ApiException a ? a.code() + ": " + a.getMessage() : e.getClass().getSimpleName() + ": " + e.getMessage();
            tx.executeWithoutResult(s -> store.updateReturning("""
                    update analytics_export_job t set status = 'FAILED', error = ?, finished_at = now(), updated_by = ?
                    where t.id = ? and t.status = 'RUNNING' returning row_to_json(t)::text""",
                    Fields.clip(msg, 4000), TenantContext.principal(), id)
                    .ifPresent(row -> outbox.publish("reporting.analytics_export.failed", "analytics_export_job", id, strip(row))));
        }
    }

    /** Processes every QUEUED job of the tenant now (recovery after a restart). */
    public ObjectNode processQueued() {
        List<JsonNode> queued = store.rows("select row_to_json(t)::text from analytics_export_job t where t.status = 'QUEUED' order by t.id limit 100");
        int ok = 0;
        for (JsonNode j : queued) {
            UUID id = UUID.fromString(j.get("id").asString());
            process(id);
            if ("SUCCEEDED".equals(store.require("analytics_export_job", id).get("status").asString())) {
                ok++;
            }
        }
        ObjectNode out = json.createObjectNode();
        out.put("processed", queued.size());
        out.put("succeeded", ok);
        out.put("failed", queued.size() - ok);
        return out;
    }

    public String cancel(UUID id, String reason) {
        JsonNode job = store.require("analytics_export_job", id);
        String st = job.get("status").asString();
        if (!Set.of("QUEUED", "RUNNING").contains(st)) {
            throw ApiException.conflict("invalid_transition", "Only queued or running exports can be cancelled (export is " + st + ")");
        }
        return store.updateReturning("""
                update analytics_export_job t set status = 'CANCELLED', error = ?, finished_at = now(), updated_by = ?
                where t.id = ? and t.status in ('QUEUED', 'RUNNING') returning row_to_json(t)::text""",
                "Cancelled by " + TenantContext.principal() + (reason == null ? "" : ": " + reason), TenantContext.principal(), id)
                .orElseThrow(() -> ApiException.conflict("invalid_transition", "The export finished meanwhile")).toString();
    }

    /** File content of a finished, unexpired export of the given format. */
    public JsonNode downloadable(UUID id, String format) {
        JsonNode job = store.require("analytics_export_job", id);
        if (!"SUCCEEDED".equals(job.get("status").asString())) {
            throw ApiException.conflict("export_not_ready", "Export is " + job.get("status").asString());
        }
        if (!format.equals(job.get("format").asString())) {
            throw new ApiException(404, "format_not_available", "Export " + id + " is a " + job.get("format").asString() + " file");
        }
        boolean expired = store.scalar("select expires_at < now() from analytics_export_job where id = ?", Boolean.class, id).orElse(true);
        if (expired) {
            throw new ApiException(410, "export_expired", "The export expired; run it again");
        }
        return job;
    }

    static ObjectNode strip(JsonNode row) {
        ObjectNode n = ((ObjectNode) row).deepCopy();
        n.remove("content");
        return n;
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
