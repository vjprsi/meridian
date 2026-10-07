package io.meridian.audit.application;

import io.meridian.audit.domain.AuditRecord;
import io.meridian.audit.domain.HashChain;
import io.meridian.audit.domain.SealManifest;
import io.meridian.audit.infrastructure.AuditEventStore;
import io.meridian.audit.infrastructure.WormStore;
import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Audit trail integrity operations (GAP-07), per tenant:
 * <ul>
 *   <li><b>Chain verification</b> (daily schedule and on demand): incremental from the last valid run, whose recorded
 *       head hash must still be the stored hash of that event (rewriting already verified history is caught too); a
 *       full run re-checks from the first event. Every run is recorded ({@code audit_chain_verification}, event
 *       {@code platform.audit_chain.verified}); a break also raises {@code platform.audit_chain.broken} and the metric
 *       {@code meridian.audit.chain.breaks}.</li>
 *   <li><b>Daily seals</b>: every event recorded up to the end of a UTC day and not sealed yet is written to WORM
 *       object storage as one object of canonical hash-chain lines, plus a manifest (content SHA-256, sequence range,
 *       chain anchors, previous seal's manifest hash). The manifest hash is kept in {@code audit_seal} and published in
 *       {@code platform.audit_seal.created}. A broken chain is never sealed.</li>
 *   <li><b>Seal verification</b>: re-reads both objects and checks content hash, manifest hash, the chain inside the
 *       batch and the database's stored hash at the batch end; a mismatch raises {@code platform.audit_seal.tampered}
 *       and {@code meridian.audit.seal.tampered}.</li>
 * </ul>
 */
@Service
public class AuditIntegrityService {
    private static final Logger log = LoggerFactory.getLogger(AuditIntegrityService.class);
    static final int BATCH = 10_000;
    static final int DEFAULT_RETENTION_YEARS = 10;

    private final AuditEventStore store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final JsonMapper json;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final WormStore worm;
    private final Counter chainBreaks;
    private final Counter sealTampered;
    private final Counter sealsCreated;

    public AuditIntegrityService(AuditEventStore store, CrudEngine crud, OutboxWriter outbox, JsonMapper json, JdbcClient jdbc,
                                 TransactionTemplate tx, WormStore worm, MeterRegistry meters) {
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.json = json;
        this.jdbc = jdbc;
        this.tx = tx;
        this.worm = worm;
        this.chainBreaks = Counter.builder("meridian.audit.chain.breaks")
                .description("Audit hash-chain verification runs that found a break (alert on any increase)").register(meters);
        this.sealTampered = Counter.builder("meridian.audit.seal.tampered")
                .description("Sealed audit batches that no longer match their manifest or the database").register(meters);
        this.sealsCreated = Counter.builder("meridian.audit.seals.created")
                .description("Daily audit batches sealed to WORM storage").register(meters);
    }

    // ------------------------------------------------------------------ chain verification

    /** Verifies the bound tenant's chain and records the run. */
    public ObjectNode verifyChain(String trigger, boolean full) {
        UUID tenant = TenantContext.require();
        OffsetDateTime started = Clocks.now();
        Optional<JsonNode> last = full ? Optional.empty() : jdbc.sql("""
                select row_to_json(v)::text from audit_chain_verification v where valid order by to_sequence desc, created_at desc
                limit 1""").query(String.class).optional().map(json::readTree);
        long from = last.map(l -> l.get("to_sequence").asLong() + 1).orElse(1L);
        String anchor = last.map(l -> l.path("head_hash").asString(null)).orElse(HashChain.GENESIS);
        long head = store.head().map(AuditEventStore.Head::sequenceNo).orElse(0L);

        ObjectNode run = json.createObjectNode();
        run.put("trigger", trigger);
        run.put("from_sequence", from);
        run.put("to_sequence", Math.max(head, from - 1));
        run.put("started_at", started.toString());
        long checked = 0;
        HashChain.Verification broken = null;
        String headHash = anchor;
        // the last verified event must still carry the hash the previous run saw (history rewritten otherwise)
        if (from > 1 && anchor != null && !anchor.equals(store.hashAt(from - 1).orElse(null))) {
            broken = new HashChain.Verification(false, from - 1, from - 1, 0, from - 1, HashChain.BreakReason.HASH_MISMATCH, null);
        }
        for (long a = from; broken == null && a <= head; a += BATCH) {
            long b = Math.min(head, a + BATCH - 1);
            List<AuditRecord> rows = store.range(a, b);
            HashChain.Verification v = HashChain.verify(rows, a, b, headHash);
            checked += v.checked();
            if (!v.valid()) {
                broken = v;
                break;
            }
            if (rows.size() < b - a + 1) {
                broken = new HashChain.Verification(false, a, b, v.checked(), a + rows.size(), HashChain.BreakReason.SEQUENCE_GAP, null);
                break;
            }
            headHash = rows.getLast().hash();
        }
        run.put("checked", checked);
        run.put("valid", broken == null);
        run.put("head_hash", broken == null ? headHash : null);
        if (broken != null) {
            run.put("first_broken_sequence", broken.firstBrokenSequence());
            run.put("break_reason", broken.reason().name());
            if (broken.brokenEventId() != null) {
                run.put("broken_event_id", broken.brokenEventId().toString());
            }
        }
        run.put("finished_at", Clocks.now().toString());
        HashChain.Verification brk = broken;
        String row = tx.execute(s -> {
            String r = crud.insert("audit_chain_verification", run);
            JsonNode stored = json.readTree(r);
            outbox.publish("platform.audit_chain.verified", "audit_chain_verification",
                    UUID.fromString(stored.get("id").asString()), stored);
            if (brk != null) {
                ObjectNode evt = (ObjectNode) stored.deepCopy();
                evt.put("tenant_id", tenant.toString());
                evt.put("verified_by", TenantContext.principal());
                outbox.publish("platform.audit_chain.broken", "audit_event",
                        brk.brokenEventId() == null ? Ids.newId() : brk.brokenEventId(), evt);
            }
            return r;
        });
        if (brk != null) {
            chainBreaks.increment();
            log.error("AUDIT CHAIN BROKEN tenant={} sequence={} reason={}", tenant, brk.firstBrokenSequence(), brk.reason());
        }
        return (ObjectNode) json.readTree(row);
    }

    // ------------------------------------------------------------------ seals

    /** Seals everything recorded up to the end of {@code day} (UTC) that is not sealed yet; idempotent per day. */
    public ObjectNode seal(LocalDate day) {
        UUID tenant = TenantContext.require();
        if (!day.isBefore(Clocks.now().toLocalDate())) {
            throw ApiException.unprocessable("day_not_over", "Only a past UTC day can be sealed");
        }
        Optional<JsonNode> existing = jdbc.sql("select row_to_json(s)::text from audit_seal s where seal_date = ?").param(day)
                .query(String.class).optional().map(json::readTree);
        if (existing.isPresent()) {
            return (ObjectNode) existing.get();
        }
        Optional<JsonNode> previous = jdbc.sql("""
                select row_to_json(s)::text from audit_seal s where seal_date < ? order by seal_date desc limit 1""")
                .param(day).query(String.class).optional().map(json::readTree);
        if (jdbc.sql("select exists(select 1 from audit_seal where seal_date > ?)").param(day).query(Boolean.class).single()) {
            throw ApiException.conflict("later_day_sealed", "A later day is already sealed; days are sealed in order");
        }
        // the last seal that holds events anchors this batch (days without events are sealed empty)
        Optional<JsonNode> lastFull = jdbc.sql("""
                select row_to_json(s)::text from audit_seal s where to_sequence is not null order by to_sequence desc limit 1""")
                .query(String.class).optional().map(json::readTree);
        long from = lastFull.map(p -> p.get("to_sequence").asLong() + 1).orElse(1L);
        OffsetDateTime cutoff = day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        long to = Math.max(from - 1, store.lastSequenceRecordedBefore(cutoff).orElse(0L));
        String anchor = from == 1 ? HashChain.GENESIS : store.hashAt(from - 1).orElse(null);
        if (lastFull.isPresent() && !lastFull.get().get("last_hash").asString().equals(anchor)) {
            chainBreaks.increment();
            throw ApiException.conflict("chain_broken", "The event before this batch no longer carries the hash sealed on "
                    + lastFull.get().get("seal_date").asString() + "; run a chain verification");
        }
        StringBuilder content = new StringBuilder();
        String prev = anchor;
        String firstPrev = null;
        int count = 0;
        for (long a = from; a <= to; a += BATCH) {
            long b = Math.min(to, a + BATCH - 1);
            List<AuditRecord> rows = store.range(a, b);
            HashChain.Verification v = HashChain.verify(rows, a, b, prev);
            if (!v.valid() || rows.size() != b - a + 1) {
                chainBreaks.increment();
                throw ApiException.conflict("chain_broken", "The audit chain is broken at sequence "
                        + (v.valid() ? a + rows.size() : v.firstBrokenSequence()) + "; it is not sealed");
            }
            for (AuditRecord r : rows) {
                if (firstPrev == null) {
                    firstPrev = r.prevHash();
                }
                content.append(HashChain.canonical(r)).append('\n');
                count++;
            }
            prev = rows.getLast().hash();
        }
        byte[] events = content.toString().getBytes(StandardCharsets.UTF_8);
        LocalDate retainUntil = day.plusYears(retentionYears());
        String base = "audit/" + tenant + "/" + day.getYear() + "/" + String.format("%02d", day.getMonthValue()) + "/"
                + String.format("%02d", day.getDayOfMonth()) + "/";
        String contentSha = HashChain.sha256(content.toString());
        SealManifest manifest = new SealManifest(tenant, day, count == 0 ? null : from, count == 0 ? null : to, count,
                firstPrev, count == 0 ? null : prev, contentSha, base + "events.jsonl",
                previous.map(p -> p.path("manifest_sha256").asString(null)).orElse(null), retainUntil, worm.lockMode(),
                Clocks.now());
        String manifestText = manifest.canonical();
        String manifestSha = HashChain.sha256(manifestText);
        String contentUri = worm.put(base + "events.jsonl", events, "application/x-ndjson", retainUntil);
        String manifestUri = worm.put(base + "manifest.json", manifestText.getBytes(StandardCharsets.UTF_8), "application/json",
                retainUntil);
        ObjectNode seal = json.createObjectNode();
        seal.put("seal_date", day.toString());
        if (count > 0) {
            seal.put("from_sequence", from);
            seal.put("to_sequence", to);
            seal.put("first_prev_hash", firstPrev);
            seal.put("last_hash", prev);
        }
        seal.put("event_count", count);
        seal.put("content_sha256", contentSha);
        seal.put("manifest_sha256", manifestSha);
        seal.put("previous_manifest_sha256", manifest.previousManifestSha256());
        seal.put("storage_uri", contentUri);
        seal.put("manifest_uri", manifestUri);
        seal.put("object_lock_mode", worm.lockMode());
        seal.put("retain_until", retainUntil.toString());
        String row = tx.execute(s -> {
            String r = crud.insert("audit_seal", seal);
            JsonNode stored = json.readTree(r);
            outbox.publish("platform.audit_seal.created", "audit_seal", UUID.fromString(stored.get("id").asString()), stored);
            return r;
        });
        sealsCreated.increment();
        return (ObjectNode) json.readTree(row);
    }

    /** Re-reads a seal's objects and checks them against the manifest, the seal row and the database. */
    public ObjectNode verifySeal(UUID id) {
        TenantContext.require();
        JsonNode seal = jdbc.sql("select row_to_json(s)::text from audit_seal s where id = ?").param(id).query(String.class)
                .optional().map(json::readTree)
                .orElseThrow(() -> new ApiException(404, "not_found", "Audit seal " + id + " does not exist"));
        List<String> problems = new ArrayList<>();
        byte[] content = null;
        byte[] manifestBytes = null;
        try {
            content = worm.get(seal.get("storage_uri").asString());
            manifestBytes = worm.get(seal.get("manifest_uri").asString());
        } catch (ApiException e) {
            problems.add(e.code() + ": " + e.getMessage());
        }
        ObjectNode checks = json.createObjectNode();
        if (content != null && manifestBytes != null) {
            String manifestText = new String(manifestBytes, StandardCharsets.UTF_8);
            String contentText = new String(content, StandardCharsets.UTF_8);
            boolean manifestOk = HashChain.sha256(manifestText).equals(seal.get("manifest_sha256").asString());
            checks.put("manifest_hash", manifestOk);
            if (!manifestOk) {
                problems.add("manifest object does not match manifest_sha256 recorded at sealing");
            }
            JsonNode manifest = json.readTree(manifestText);
            boolean contentOk = HashChain.sha256(contentText).equals(seal.get("content_sha256").asString())
                    && manifest.path("content_sha256").asString("").equals(seal.get("content_sha256").asString());
            checks.put("content_hash", contentOk);
            if (!contentOk) {
                problems.add("events object does not match content_sha256");
            }
            // the chain inside the batch: each line's hash is the next line's prev_hash
            String prev = seal.path("first_prev_hash").asString(null);
            long expectedSeq = seal.path("from_sequence").asLong(0);
            int lines = 0;
            List<String> lineHashes = new ArrayList<>();
            boolean chainOk = true;
            for (String line : contentText.split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                JsonNode e = json.readTree(line);
                if (!e.path("prev_hash").asString("").equals(prev) || e.path("sequence_no").asLong() != expectedSeq) {
                    chainOk = false;
                    problems.add("chain inside the batch breaks at sequence " + e.path("sequence_no").asLong());
                    break;
                }
                prev = HashChain.sha256(line);
                lineHashes.add(prev);
                expectedSeq++;
                lines++;
            }
            if (chainOk && lines != seal.path("event_count").asInt()) {
                chainOk = false;
                problems.add("batch holds " + lines + " events, the seal records " + seal.path("event_count").asInt());
            }
            if (chainOk && lines > 0 && !prev.equals(seal.path("last_hash").asString())) {
                chainOk = false;
                problems.add("last event hash differs from the sealed last_hash");
            }
            checks.put("batch_chain", chainOk);
            // the database must still hold exactly the sealed history: every event, re-hashed from its stored fields,
            // equals the sealed line (catches rewritten fields even where the stored hash column was left alone)
            if (seal.hasNonNull("to_sequence") && chainOk) {
                long from = seal.get("from_sequence").asLong();
                long to = seal.get("to_sequence").asLong();
                String mismatch = null;
                for (long a = from; mismatch == null && a <= to; a += BATCH) {
                    List<AuditRecord> rows = store.range(a, Math.min(to, a + BATCH - 1));
                    if (rows.size() != Math.min(to, a + BATCH - 1) - a + 1) {
                        mismatch = "events are missing from the database between " + a + " and " + Math.min(to, a + BATCH - 1);
                        break;
                    }
                    for (AuditRecord r : rows) {
                        String sealed = lineHashes.get((int) (r.sequenceNo() - from));
                        if (!sealed.equals(HashChain.hash(r)) || !sealed.equals(r.hash())) {
                            mismatch = "the stored event " + r.sequenceNo() + " differs from the sealed copy";
                            break;
                        }
                    }
                }
                checks.put("database_matches", mismatch == null);
                if (mismatch != null) {
                    problems.add(mismatch);
                }
            }
        }
        ObjectNode out = json.createObjectNode();
        out.put("audit_seal_id", id.toString());
        out.put("seal_date", seal.get("seal_date").asString());
        out.put("valid", problems.isEmpty());
        out.set("checks", checks);
        ArrayNode p = out.putArray("problems");
        problems.forEach(p::add);
        out.put("verified_at", Clocks.now().toString());
        if (!problems.isEmpty()) {
            sealTampered.increment();
            log.error("AUDIT SEAL TAMPERED seal={} date={} problems={}", id, seal.get("seal_date").asString(), problems);
            ObjectNode evt = ((ObjectNode) seal.deepCopy());
            evt.set("problems", p.deepCopy());
            tx.executeWithoutResult(s -> outbox.publish("platform.audit_seal.tampered", "audit_seal", id, evt));
        }
        return out;
    }

    private int retentionYears() {
        return jdbc.sql("""
                select retention_years from audit_retention_policy where resource_type = '*' and country is null limit 1""")
                .query(Integer.class).optional().orElse(DEFAULT_RETENTION_YEARS);
    }
}
