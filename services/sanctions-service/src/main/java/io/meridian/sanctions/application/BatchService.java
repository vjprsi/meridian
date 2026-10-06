package io.meridian.sanctions.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.sanctions.infrastructure.Db;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bulk screening: the batch is stored QUEUED and processed in the background (virtual threads, bounded to a few
 * concurrent batches); each subject is screened in its own transaction and counted. A subject that fails is counted
 * as an error and does not stop the batch.
 */
@Service
public class BatchService {
    private static final Logger log = LoggerFactory.getLogger(BatchService.class);
    static final int MAX_SUBJECTS = 10_000;

    private final ScreeningService screening;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Db db;
    private final JsonMapper json;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore running = new Semaphore(4);

    public BatchService(ScreeningService screening, CrudEngine crud, OutboxWriter outbox, TransactionTemplate tx, Db db,
                        JsonMapper json) {
        this.screening = screening;
        this.crud = crud;
        this.outbox = outbox;
        this.tx = tx;
        this.db = db;
        this.json = json;
    }

    public String submit(JsonNode subjects, String trigger, BigDecimal threshold) {
        UUID tenant = TenantContext.require();
        if (subjects == null || !subjects.isArray() || subjects.isEmpty()) {
            throw ApiException.unprocessable("subjects_required", "subjects must be a non-empty array");
        }
        if (subjects.size() > MAX_SUBJECTS) {
            throw ApiException.unprocessable("batch_too_large", "At most " + MAX_SUBJECTS + " subjects per batch");
        }
        int i = 0;
        for (JsonNode s : subjects) {
            i++;
            if (!s.hasNonNull("subject_id") || !s.hasNonNull("full_name") || !s.hasNonNull("subject_type")) {
                throw ApiException.unprocessable("invalid_subject", "Subject " + i + " needs subject_type, subject_id and full_name");
            }
        }
        if (threshold != null) {
            ScreeningService.validThreshold(threshold.doubleValue());
        }
        ObjectNode v = json.createObjectNode();
        v.put("trigger", trigger);
        v.put("status", "QUEUED");
        v.put("total", subjects.size());
        ArrayNode stored = json.createArrayNode();
        subjects.forEach(s -> {
            ObjectNode c = (ObjectNode) s.deepCopy();
            if (threshold != null && !c.hasNonNull("threshold")) {
                c.put("threshold", threshold.toPlainString());
            }
            stored.add(c);
        });
        v.set("subjects", stored);
        String row = tx.execute(s -> crud.insert("screening_batch", v));
        UUID id = UUID.fromString(json.readTree(row).get("id").asString());
        String principal = TenantContext.principal();
        executor.submit(() -> {
            try {
                running.acquire();
                try {
                    TenantContext.callAs(tenant, principal, () -> {
                        run(id);
                        return null;
                    });
                } finally {
                    running.release();
                }
            } catch (Exception e) {
                log.error("screening batch {} failed", id, e);
            }
        });
        return row;
    }

    void run(UUID id) {
        JsonNode batch = db.require("screening_batch", id, false);
        db.update("update screening_batch set status = 'RUNNING', started_at = now() where id = ? and status = 'QUEUED'", id);
        String trigger = batch.get("trigger").asString();
        int processed = 0;
        int potential = 0;
        int errors = 0;
        for (JsonNode s : batch.path("subjects")) {
            ObjectNode body = (ObjectNode) s.deepCopy();
            body.put("trigger", trigger);
            body.put("screening_batch_id", id.toString());
            if (!body.hasNonNull("requested_by_service")) {
                body.put("requested_by_service", "batch");
            }
            try {
                JsonNode r = screening.screenSubject(body);
                if ("POTENTIAL_MATCH".equals(r.path("result").path("status").asString())) {
                    potential++;
                }
            } catch (RuntimeException e) {
                errors++;
                log.warn("batch {} subject {} failed: {}", id, s.path("subject_id").asString(), e.getMessage());
            }
            processed++;
            if (processed % 200 == 0) {
                db.update("update screening_batch set processed = ?, potential_matches = ?, errors = ? where id = ?",
                        processed, potential, errors, id);
            }
        }
        int p = processed;
        int pm = potential;
        int er = errors;
        tx.executeWithoutResult(st -> {
            String status = er == 0 ? "SUCCEEDED" : er == p ? "FAILED" : "PARTIALLY_SUCCEEDED";
            db.update("""
                    update screening_batch set status = ?, processed = ?, potential_matches = ?, errors = ?,
                        finished_at = now(), subjects = '[]'::jsonb, error = ? where id = ?""",
                    status, p, pm, er, er == 0 ? null : er + " subjects failed", id);
            JsonNode row = db.require("screening_batch", id, false);
            outbox.publish("risk.screening_batch.completed", "screening_batch", id, row);
        });
    }
}
