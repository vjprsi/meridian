package io.meridian.search.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.search.domain.IndexRules;
import io.meridian.search.infrastructure.DocumentStore;
import io.meridian.search.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import jakarta.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Index jobs, executed asynchronously:
 * <ul>
 *   <li>FULL_REINDEX — builds a new physical index from the ingest journal (latest state per entity) with the
 *       current mapping while reads keep using the old one and new changes are dual-written, then swaps the
 *       alias atomically and drops the old physical index (zero downtime).</li>
 *   <li>INCREMENTAL (catch-up) — re-applies journalled events from a timestamp to the live physical index.</li>
 * </ul>
 * Jobs check for cancellation between batches; a cancelled reindex discards the partial index.
 */
@Service
public class IndexJobs {
    private static final Logger log = LoggerFactory.getLogger(IndexJobs.class);

    private final Rows rows;
    private final CrudEngine crud;
    private final DocumentStore docs;
    private final IndexService indexes;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final int batchSize;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public IndexJobs(Rows rows, CrudEngine crud, DocumentStore docs, IndexService indexes, OutboxWriter outbox,
                     TransactionTemplate tx, JsonMapper json, @Value("${meridian.search.batch-size:500}") int batchSize) {
        this.rows = rows;
        this.crud = crud;
        this.docs = docs;
        this.indexes = indexes;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
        this.batchSize = Math.max(1, batchSize);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public String reindex(UUID indexId) {
        return tx.execute(s -> {
            JsonNode index = rows.require("search_index", indexId, true);
            IndexRules.requireReindexable(index.get("status").asString());
            requireNoRunningJob(indexId);
            String target = IndexRules.physicalName(index.get("alias_name").asString(), index.get("mapping_version").asInt());
            ObjectNode job = newJob(indexId, "FULL_REINDEX");
            job.put("target_index", target);
            String row = crud.insert("search_index_job", job);
            rows.update("update search_index set status = 'REINDEXING' where id = ?", indexId);
            launch(UUID.fromString(job.get("id").asString()));
            return row;
        });
    }

    public String catchUp(UUID indexId, OffsetDateTime from) {
        if (from.isAfter(OffsetDateTime.now())) {
            throw ApiException.unprocessable("from_timestamp_in_future", "from_timestamp must not be in the future");
        }
        return tx.execute(s -> {
            JsonNode index = rows.require("search_index", indexId, true);
            if (!IndexRules.searchable(index.get("status").asString())) {
                throw ApiException.conflict("invalid_transition", "Index in status " + index.get("status").asString()
                        + " cannot catch up");
            }
            requireNoRunningJob(indexId);
            ObjectNode job = newJob(indexId, "INCREMENTAL");
            job.put("from_timestamp", from.toString());
            job.put("target_index", index.get("physical_index").asString());
            String row = crud.insert("search_index_job", job);
            launch(UUID.fromString(job.get("id").asString()));
            return row;
        });
    }

    public String cancel(UUID jobId) {
        return tx.execute(s -> {
            JsonNode job = rows.require("search_index_job", jobId, true);
            IndexRules.requireCancellable(job.get("status").asString());
            String row = rows.updateReturning("""
                    update search_index_job set status = 'CANCELLED', finished_at = now(), error = ?
                    where id = ? returning row_to_json(search_index_job)::text""",
                    "Cancelled by " + TenantContext.principal(), jobId);
            if ("FULL_REINDEX".equals(job.get("job_type").asString())) {
                rows.update("update search_index set status = 'ACTIVE' where id = ? and status = 'REINDEXING'",
                        UUID.fromString(job.get("search_index_id").asString()));
            }
            return row;
        });
    }

    private void requireNoRunningJob(UUID indexId) {
        rows.queryOne("""
                select row_to_json(j)::text from search_index_job j where search_index_id = ?
                and status in ('QUEUED', 'RUNNING') and job_type in ('FULL_REINDEX', 'INCREMENTAL') limit 1""", indexId)
                .ifPresent(j -> {
                    throw ApiException.conflict("job_in_progress", "Index job " + j.get("id").asString() + " is still running");
                });
    }

    private ObjectNode newJob(UUID indexId, String type) {
        ObjectNode job = json.createObjectNode();
        job.put("id", io.meridian.common.id.Ids.newId().toString());
        job.put("search_index_id", indexId.toString());
        job.put("job_type", type);
        job.put("status", "QUEUED");
        job.put("documents_processed", 0);
        job.put("documents_failed", 0);
        return job;
    }

    private void launch(UUID jobId) {
        UUID tenant = TenantContext.require();
        String principal = TenantContext.principal();
        Runnable run = () -> executor.submit(() -> TenantContext.callAs(tenant, principal, () -> {
            execute(jobId);
            return null;
        }));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    run.run();
                }
            });
        } else {
            run.run();
        }
    }

    void execute(UUID jobId) {
        JsonNode job = rows.require("search_index_job", jobId, false);
        UUID indexId = UUID.fromString(job.get("search_index_id").asString());
        if (rows.update("""
                update search_index_job set status = 'RUNNING', started_at = now() where id = ? and status = 'QUEUED'""",
                jobId) == 0) {
            return; // cancelled before it started
        }
        try {
            if ("FULL_REINDEX".equals(job.get("job_type").asString())) {
                fullReindex(jobId, indexId, job.get("target_index").asString());
            } else {
                catchUp(jobId, indexId, OffsetDateTime.parse(job.get("from_timestamp").asString()));
            }
        } catch (RuntimeException e) {
            log.error("Search index job {} failed", jobId, e);
            tx.executeWithoutResult(s -> {
                rows.update("""
                        update search_index_job set status = 'FAILED', finished_at = now(), error = ?
                        where id = ? and status = 'RUNNING'""", String.valueOf(e.getMessage()), jobId);
                rows.update("update search_index set status = 'ACTIVE' where id = ? and status = 'REINDEXING'", indexId);
            });
            if ("FULL_REINDEX".equals(job.get("job_type").asString())) {
                docs.dropIndex(job.get("target_index").asString());
            }
        }
    }

    private void fullReindex(UUID jobId, UUID indexId, String target) {
        JsonNode index = rows.require("search_index", indexId, false);
        String type = index.get("entity_type").asString();
        // replay the whole journal in ingest order: the same version guards as live ingestion decide the final
        // state of every entity (stale events never win, deletes are honoured)
        OffsetDateTime origin = OffsetDateTime.parse("1970-01-01T00:00:00Z");
        UUID after = null;
        long processed = 0;
        long failed = 0;
        while (true) {
            if (cancelled(jobId)) {
                docs.dropIndex(target);
                return;
            }
            List<DocumentStore.IngestEntry> batch = docs.since(type, origin, after, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            for (DocumentStore.IngestEntry e : batch) {
                try {
                    indexes.apply(index, target, type, e.entityId(), e.document(), "DELETE".equals(e.operation()),
                            e.eventId(), e.version(), e.occurredAt());
                    processed++;
                } catch (RuntimeException ex) {
                    failed++;
                    log.warn("Reindex of {} {} failed: {}", type, e.entityId(), ex.getMessage());
                }
                after = e.id();
            }
            progress(jobId, processed, failed);
        }
        long done = processed;
        long bad = failed;
        boolean swapped = Boolean.TRUE.equals(tx.execute(s -> {
            JsonNode current = rows.require("search_index", indexId, true);
            JsonNode j = rows.require("search_index_job", jobId, true);
            if (!"RUNNING".equals(j.get("status").asString())) {
                return false;
            }
            String old = current.get("physical_index").asString();
            String row = rows.updateReturning("""
                    update search_index set physical_index = ?, status = 'ACTIVE', document_count = ?, last_indexed_at = now()
                    where id = ? returning row_to_json(search_index)::text""", target, docs.count(target), indexId);
            docs.dropIndex(old);
            rows.update("""
                    update search_index_job set status = ?, finished_at = now(), documents_processed = ?, documents_failed = ?
                    where id = ?""", bad == 0 ? "SUCCEEDED" : "PARTIALLY_SUCCEEDED", done, bad, jobId);
            ObjectNode payload = (ObjectNode) json.readTree(row);
            payload.put("previous_physical_index", old);
            payload.put("search_index_job_id", jobId.toString());
            outbox.publish("operations.search_index.reindexed", "search_index", indexId, payload);
            return true;
        }));
        if (!swapped) {
            docs.dropIndex(target);
        }
    }

    private void catchUp(UUID jobId, UUID indexId, OffsetDateTime from) {
        JsonNode index = rows.require("search_index", indexId, false);
        String type = index.get("entity_type").asString();
        String physical = index.get("physical_index").asString();
        UUID after = null;
        long processed = 0;
        long failed = 0;
        while (true) {
            if (cancelled(jobId)) {
                return;
            }
            List<DocumentStore.IngestEntry> batch = docs.since(type, from, after, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            for (DocumentStore.IngestEntry e : batch) {
                try {
                    indexes.apply(index, physical, type, e.entityId(), e.document(), "DELETE".equals(e.operation()),
                            e.eventId(), e.version(), e.occurredAt());
                    processed++;
                } catch (RuntimeException ex) {
                    failed++;
                }
                after = e.id();
            }
            progress(jobId, processed, failed);
        }
        rows.update("""
                update search_index_job set status = ?, finished_at = now(), documents_processed = ?, documents_failed = ?
                where id = ? and status = 'RUNNING'""", failed == 0 ? "SUCCEEDED" : "PARTIALLY_SUCCEEDED", processed, failed, jobId);
        rows.update("update search_index set document_count = ?, last_indexed_at = now() where id = ?", docs.count(physical), indexId);
    }

    private boolean cancelled(UUID jobId) {
        return !"RUNNING".equals(rows.require("search_index_job", jobId, false).get("status").asString());
    }

    private void progress(UUID jobId, long processed, long failed) {
        rows.update("update search_index_job set documents_processed = ?, documents_failed = ? where id = ? and status = 'RUNNING'",
                processed, failed, jobId);
    }
}
