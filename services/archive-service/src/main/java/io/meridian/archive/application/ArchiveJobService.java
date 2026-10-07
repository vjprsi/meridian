package io.meridian.archive.application;

import io.meridian.archive.domain.ArchiveRules;
import io.meridian.archive.domain.Certificate;
import io.meridian.archive.domain.HoldMatcher;
import io.meridian.archive.infrastructure.Rows;
import io.meridian.archive.infrastructure.SourceClient;
import io.meridian.archive.infrastructure.WormStore;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
 * Archive runs: pull aged records (older than the cutoff, excluding legal-hold data) from the owning service in
 * batches, write each batch as an immutable checksummed object to WORM storage with an object-lock date derived
 * from the retention policy, record its manifest (archived_object), and confirm the batch to the source.
 */
@Service
public class ArchiveJobService {
    private static final Logger log = LoggerFactory.getLogger(ArchiveJobService.class);

    private final Rows rows;
    private final CrudEngine crud;
    private final LegalHoldService holds;
    private final SourceClient source;
    private final WormStore worm;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final int batchSize;
    private final String kmsKeyPrefix;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ArchiveJobService(Rows rows, CrudEngine crud, LegalHoldService holds, SourceClient source, WormStore worm,
                             OutboxWriter outbox, TransactionTemplate tx, JsonMapper json,
                             @Value("${meridian.archive.batch-size:1000}") int batchSize,
                             @Value("${meridian.archive.kms-key-prefix:kms://archive}") String kmsKeyPrefix) {
        this.rows = rows;
        this.crud = crud;
        this.holds = holds;
        this.source = source;
        this.worm = worm;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
        this.batchSize = Math.max(1, batchSize);
        this.kmsKeyPrefix = kmsKeyPrefix;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Validates and queues a run; it executes asynchronously after commit. */
    public String create(ObjectNode v) {
        return tx.execute(s -> {
            JsonNode policy = rows.text("retention_policy", UUID.fromString(v.get("retention_policy_id").asString()), false)
                    .map(rows::tree).orElseThrow(() -> ApiException.unprocessable("unknown_policy", "retention_policy_id does not exist"));
            JsonNode cls = rows.text("data_classification", UUID.fromString(v.get("data_classification_id").asString()), false)
                    .map(rows::tree).orElseThrow(() -> ApiException.unprocessable("unknown_classification", "data_classification_id does not exist"));
            if (!policy.path("is_active").asBoolean(true)) {
                throw ApiException.unprocessable("policy_inactive", "The retention policy is not active");
            }
            if (!policy.get("data_class").asString().equals(cls.get("data_class").asString())) {
                throw ApiException.unprocessable("class_mismatch", "The policy covers " + policy.get("data_class").asString()
                        + " but the table is classified " + cls.get("data_class").asString());
            }
            if (!"ARCHIVE".equals(policy.get("action").asString()) && !"ARCHIVE_THEN_PURGE".equals(policy.get("action").asString())) {
                throw ApiException.unprocessable("policy_does_not_archive", "The policy action is " + policy.get("action").asString());
            }
            LocalDate cutoff = LocalDate.parse(v.get("cutoff_date").asString());
            LocalDate latest = ArchiveRules.archiveCutoff(LocalDate.now(), policy.get("online_days").asInt());
            if (cutoff.isAfter(latest)) {
                throw ApiException.unprocessable("cutoff_within_online_period", "Records newer than " + latest
                        + " are still in the " + policy.get("online_days").asInt() + "-day online period");
            }
            rows.queryOne("""
                    select row_to_json(j)::text from archive_job j where data_classification_id = ?
                    and status in ('QUEUED', 'RUNNING') limit 1""", UUID.fromString(cls.get("id").asString()))
                    .ifPresent(j -> {
                        throw ApiException.conflict("job_in_progress", "Archive job " + j.get("id").asString() + " is running for this table");
                    });
            UUID id = Ids.newId();
            v.put("id", id.toString());
            v.put("status", "QUEUED");
            v.put("records_archived", 0);
            v.put("records_skipped_hold", 0);
            v.put("bytes_archived", 0);
            String row = crud.insert("archive_job", v);
            launch(id);
            return row;
        });
    }

    public String cancel(UUID id) {
        return tx.execute(s -> {
            JsonNode job = rows.require("archive_job", id, true);
            ArchiveRules.requireJobCancel(job.get("status").asString());
            return rows.updateReturning("""
                    update archive_job set status = 'CANCELLED', finished_at = now(), error = ?
                    where id = ? returning row_to_json(archive_job)::text""", "Cancelled by " + TenantContext.principal(), id);
        });
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
        if (rows.update("update archive_job set status = 'RUNNING', started_at = now() where id = ? and status = 'QUEUED'", jobId) == 0) {
            return;
        }
        JsonNode job = rows.require("archive_job", jobId, false);
        try {
            run(job);
        } catch (RuntimeException e) {
            log.warn("Archive job {} failed: {}", jobId, e.getMessage());
            finish(jobId, "FAILED", e instanceof ApiException a ? a.code() + ": " + a.getMessage() : String.valueOf(e.getMessage()));
        }
    }

    private void run(JsonNode job) {
        UUID jobId = UUID.fromString(job.get("id").asString());
        JsonNode policy = rows.require("retention_policy", UUID.fromString(job.get("retention_policy_id").asString()), false);
        JsonNode cls = rows.require("data_classification", UUID.fromString(job.get("data_classification_id").asString()), false);
        String service = cls.get("service_name").asString();
        String db = cls.get("database_name").asString();
        String table = cls.get("table_name").asString();
        HoldMatcher.Decision d = HoldMatcher.decide(holds.activeHolds(), db, table, Map.of());
        if (d.blocked()) {
            finish(jobId, "SUCCEEDED", "Nothing archived: legal hold " + d.blockedBy().id() + " covers " + db + "." + table);
            return;
        }
        ObjectNode exclude = json.createObjectNode();
        d.exclusions().forEach((scope, ids) -> {
            var a = exclude.putArray(HoldMatcher.SELECTOR_FIELDS.getOrDefault(scope, scope.toLowerCase(java.util.Locale.ROOT) + "_ids"));
            ids.forEach(a::add);
        });
        String afterId = null;
        int batchNo = 0;
        while (true) {
            if (!"RUNNING".equals(rows.require("archive_job", jobId, false).get("status").asString())) {
                return; // cancelled: objects already written stay (WORM), the rest stays online
            }
            ObjectNode req = json.createObjectNode();
            req.put("archive_job_id", jobId.toString());
            req.put("database_name", db);
            req.put("table_name", table);
            req.put("created_before", job.get("cutoff_date").asString());
            req.put("after_id", afterId);
            req.put("limit", batchSize);
            req.set("exclude", exclude);
            batchNo++;
            JsonNode res = source.extract(service, req, "arj-" + jobId + "-" + batchNo);
            long skipped = res == null ? 0 : res.path("skipped_hold").asLong(0);
            JsonNode records = res == null ? null : res.get("records");
            if (skipped > 0) {
                rows.update("update archive_job set records_skipped_hold = records_skipped_hold + ? where id = ?", skipped, jobId);
            }
            if (records == null || !records.isArray() || records.isEmpty()) {
                break;
            }
            afterId = writeBatch(jobId, batchNo, service, db, table, cls, policy, records,
                    job.get("cutoff_date").asString(), exclude);
            if (!res.path("has_more").asBoolean(false)) {
                break;
            }
        }
        finish(jobId, "SUCCEEDED", null);
    }

    /** Writes one immutable batch and its manifest; returns the last record id. */
    private String writeBatch(UUID jobId, int batchNo, String service, String db, String table, JsonNode cls, JsonNode policy,
                              JsonNode records, String cutoff, JsonNode exclude) {
        StringBuilder lines = new StringBuilder();
        OffsetDateTime min = null;
        OffsetDateTime max = null;
        for (JsonNode r : records) {
            if (!r.hasNonNull("id") || !r.hasNonNull("created_at")) {
                throw new ApiException(502, "invalid_extract", service + " returned a record without id/created_at");
            }
            try {
                UUID.fromString(r.get("id").asString());
            } catch (IllegalArgumentException e) {
                throw new ApiException(502, "invalid_extract", service + " returned a non-UUID record id " + r.get("id").asString());
            }
            OffsetDateTime c = OffsetDateTime.parse(r.get("created_at").asString());
            min = min == null || c.isBefore(min) ? c : min;
            max = max == null || c.isAfter(max) ? c : max;
            lines.append(Certificate.canonical(r)).append('\n');
        }
        byte[] bytes = lines.toString().getBytes(StandardCharsets.UTF_8);
        String checksum = Certificate.sha256(bytes);
        LocalDate retain = ArchiveRules.wormRetainUntil(max.toLocalDate(), policy.get("retention_years").asInt());
        UUID tenant = TenantContext.require();
        String uri = worm.put(tenant + "/" + db + "/" + table + "/" + jobId + "/batch-" + batchNo + ".jsonl", bytes, retain);
        String first = records.get(0).get("id").asString();
        String last = records.get(records.size() - 1).get("id").asString();
        OffsetDateTime fMin = min;
        OffsetDateTime fMax = max;
        String objectRow = tx.execute(s -> {
            ObjectNode o = json.createObjectNode();
            o.put("archive_job_id", jobId.toString());
            o.put("database_name", db);
            o.put("table_name", table);
            o.put("first_record_id", first);
            o.put("last_record_id", last);
            o.put("record_count", records.size());
            o.put("min_created_at", fMin.toString());
            o.put("max_created_at", fMax.toString());
            o.put("storage_uri", uri);
            o.put("storage_class", policy.get("retention_years").asInt() > 7 ? "DEEP_ARCHIVE" : "ARCHIVE");
            o.put("checksum_sha256", checksum);
            o.put("encryption_key_ref", kmsKeyPrefix + "/" + tenant + "/" + cls.get("data_class").asString().toLowerCase(java.util.Locale.ROOT));
            o.put("worm_retain_until", retain.toString());
            o.put("restorable", true);
            String row = crud.insert("archived_object", o);
            rows.update("""
                    update archive_job set records_archived = records_archived + ?, bytes_archived = bytes_archived + ?
                    where id = ?""", records.size(), bytes.length, jobId);
            return row;
        });
        ObjectNode confirm = json.createObjectNode();
        confirm.put("archive_job_id", jobId.toString());
        confirm.put("archived_object_id", json.readTree(objectRow).get("id").asString());
        confirm.put("table_name", table);
        confirm.put("first_record_id", first);
        confirm.put("last_record_id", last);
        confirm.put("record_count", records.size());
        confirm.put("checksum_sha256", checksum);
        confirm.put("created_before", cutoff);
        confirm.set("exclude", exclude);
        source.confirm(service, confirm, "arj-" + jobId + "-" + batchNo + "-confirm");
        return last;
    }

    private void finish(UUID jobId, String status, String note) {
        tx.executeWithoutResult(s -> {
            List<String> updated = rows.query("""
                    update archive_job set status = ?, finished_at = now(), error = ? where id = ? and status = 'RUNNING'
                    returning row_to_json(archive_job)::text""", status, note, jobId).stream().map(JsonNode::toString).toList();
            if (!updated.isEmpty()) {
                outbox.publish("operations.archive_job.completed", "archive_job", jobId, json.readTree(updated.getFirst()));
            }
        });
    }
}
