package io.meridian.mover;

import io.meridian.common.time.Clocks;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Copies one tenant from its source cell to the target cell (MR-GAME-DAY), as a resumable state machine persisted in
 * {@link ProgressStore}:
 * <ol>
 *   <li><b>PENDING</b> until {@code not_before_checkpoint} (freeze + grace: no write admitted before the freeze can still
 *       land in the source);</li>
 *   <li><b>COPYING</b>: plans every service's tenant tables (parents first; both cells must run the same schema
 *       version; the target must not hold rows of the tenant yet), then pages each table out of the source
 *       ({@code /v1/internal/data-lifecycle/move/extract}, RLS-scoped to the tenant, id order) and bulk-loads it into
 *       the target ({@code /move/import}: insert-if-absent, so a resumed or repeated page is harmless). Rows whose
 *       parent is not there yet are retried at the end of the service. Then copies the tenant's document prefix
 *       ({@code <tenant>/}) between the cells' buckets, skipping objects already present with the same ETag;</li>
 *   <li><b>VERIFYING</b>: re-reads both cells and compares per-table row counts and SHA-256 content digests over the
 *       canonical JSON of every row (configured rewrites applied to the source side), and the document listings (key,
 *       size, ETag);</li>
 *   <li><b>CONFIRMING</b>: reports {@code /confirm-copy} with the checkpoint {@code mover:v1 rows=… tables=… docs=…
 *       sha256=<manifest digest>} (the full manifest stays in the mover database);</li>
 *   <li><b>COMPLETED</b>, or <b>FAILED</b> (needs an operator: schema drift, target not empty, verification
 *       mismatch, refused import) / <b>CANCELLED</b> (the move was cancelled).</li>
 * </ol>
 * Progress is written after every page; a crash or a transient failure (network, 5xx) releases the lease and the next
 * resume tick (any instance) continues from the last page. Copy and verification are throttled
 * ({@code rows-per-second}, {@code objects-per-second}).
 */
public class TenantMover {
    private static final Logger log = LoggerFactory.getLogger(TenantMover.class);
    static final String MOVE = "/v1/internal/data-lifecycle/move/";

    private final ProgressStore store;
    private final Cells cells;
    private final MoverHttp http;
    private final MoverProperties props;
    private final JsonMapper json;
    private final Throttle rows;
    private final Throttle objects;
    private final String owner;
    private io.micrometer.core.instrument.MeterRegistry meters;

    /** Thrown when the run was cancelled while this instance worked on it. */
    static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("move cancelled");
        }
    }

    /** Per-run context: the two cells, their object stores and the effective rewrites. */
    record Ctx(ProgressStore.Run run, Cells.Cell source, Cells.Cell target, ObjectStore sourceDocs,
               ObjectStore targetDocs, List<MoverProperties.Rewrite> rewrites) {}

    public TenantMover(ProgressStore store, Cells cells, MoverHttp http, MoverProperties props, String owner) {
        this.store = store;
        this.cells = cells;
        this.http = http;
        this.props = props;
        this.json = http.json();
        this.rows = new Throttle(props.rowsPerSecond());
        this.objects = new Throttle(props.objectsPerSecond());
        this.owner = owner;
    }

    public void bindMetrics(io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
    }

    private void count(String name, long n, String... tags) {
        if (meters != null && n > 0) {
            meters.counter(name, tags).increment(n);
        }
    }

    /** Registers a copy request from {@code platform.tenant_move.copy_requested}; false when already known. */
    public boolean register(JsonNode handoff) {
        UUID moveId = UUID.fromString(handoff.get("id").asString());
        UUID tenant = UUID.fromString(handoff.get("subject_tenant_id").asString());
        String from = handoff.path("from_cell").path("code").asString("");
        String to = handoff.path("to_cell").path("code").asString("");
        String strategy = handoff.path("copy_strategy").asString("LOGICAL_REPLICATION");
        String nb = handoff.path("not_before_checkpoint").asString(null);
        boolean fresh = store.register(moveId, tenant, from, to, strategy, handoff,
                nb == null ? Clocks.now() : java.time.OffsetDateTime.parse(nb));
        log.info("Tenant move {} (tenant {}, {} -> {}, {}): copy request {}", moveId, tenant, from, to, strategy,
                fresh ? "registered" : "already known");
        return fresh;
    }

    /** Cancels a run (the move was cancelled in tenant-service). */
    public void cancel(UUID moveId) {
        store.run(moveId).filter(r -> List.of("PENDING", "COPYING", "VERIFYING", "CONFIRMING").contains(r.status()))
                .ifPresent(r -> {
                    store.status(moveId, "CANCELLED", "tenant move cancelled");
                    log.warn("Tenant move {} cancelled: copy stopped", moveId);
                });
    }

    /** Open runs that are due and not leased by a live instance. */
    public List<UUID> dueRuns() {
        return store.due();
    }

    /** Leases and advances one run as far as it goes; returns the status afterwards. */
    public Optional<String> runIfDue(UUID moveId) {
        Optional<ProgressStore.Run> claimed = store.claim(moveId, owner, props.lease());
        if (claimed.isEmpty()) {
            return store.run(moveId).map(ProgressStore.Run::status);
        }
        ProgressStore.Run run = claimed.get();
        try {
            Ctx ctx = context(run);
            if ("PENDING".equals(run.status())) {
                store.status(moveId, "COPYING", null);
            }
            run = reload(moveId);
            if ("COPYING".equals(run.status())) {
                copy(ctx);
                store.status(moveId, "VERIFYING", null);
            }
            run = reload(moveId);
            if ("VERIFYING".equals(run.status())) {
                verify(new Ctx(run, ctx.source(), ctx.target(), ctx.sourceDocs(), ctx.targetDocs(), ctx.rewrites()));
                store.status(moveId, "CONFIRMING", null);
            }
            run = reload(moveId);
            if ("CONFIRMING".equals(run.status())) {
                confirm(run);
            }
        } catch (Cancelled e) {
            log.info("Tenant move {}: run stopped (cancelled)", moveId);
        } catch (MoverHttp.TransientException e) {
            log.warn("Tenant move {}: transient failure, will resume: {}", moveId, e.getMessage());
            store.release(moveId, e.getMessage(), null);
        } catch (MoverHttp.PermanentException e) {
            log.error("Tenant move {} FAILED: {}", moveId, e.getMessage());
            store.status(moveId, "FAILED", e.getMessage());
            count("meridian.mover.runs", 1, "outcome", "failed");
        } catch (RuntimeException e) {
            log.warn("Tenant move {}: unexpected failure, will resume: {}", moveId, e.toString());
            store.release(moveId, e.toString(), null);
        }
        return store.run(moveId).map(ProgressStore.Run::status);
    }

    private ProgressStore.Run reload(UUID moveId) {
        ProgressStore.Run r = store.run(moveId).orElseThrow();
        if ("CANCELLED".equals(r.status())) {
            throw new Cancelled();
        }
        return r;
    }

    private Ctx context(ProgressStore.Run run) {
        Cells.Cell source = cells.resolve(run.request().path("from_cell"));
        Cells.Cell target = cells.resolve(run.request().path("to_cell"));
        ObjectStore sd = source.s3() == null || source.s3().bucket() == null ? null : new ObjectStore(source.s3());
        ObjectStore td = target.s3() == null || target.s3().bucket() == null ? null : new ObjectStore(target.s3());
        List<MoverProperties.Rewrite> rw = new ArrayList<>();
        for (MoverProperties.Rewrite r : props.rewrites()) {
            if ((r.from() + r.to()).contains("_bucket}") && (sd == null || td == null)) {
                continue;
            }
            String from = r.from().replace("{source_bucket}", sd == null ? "" : sd.bucket())
                    .replace("{target_bucket}", td == null ? "" : td.bucket());
            String to = r.to().replace("{source_bucket}", sd == null ? "" : sd.bucket())
                    .replace("{target_bucket}", td == null ? "" : td.bucket());
            if (!from.equals(to)) {
                rw.add(new MoverProperties.Rewrite(r.service(), r.table(), r.column(), from, to));
            }
        }
        return new Ctx(run, source, target, sd, td, rw);
    }

    private void heartbeat(UUID moveId) {
        store.heartbeat(moveId, owner, props.lease());
        reload(moveId);
    }

    // ------------------------------------------------------------------ copy

    private boolean copiesRows(ProgressStore.Run run) {
        return !"NONE".equals(run.strategy());     // NONE: both cells share the database cluster
    }

    void copy(Ctx ctx) {
        ProgressStore.Run run = ctx.run();
        UUID tenant = run.tenantId();
        if (copiesRows(run) && !run.planned()) {
            plan(ctx);
        }
        Map<String, List<ProgressStore.Item>> byService = new LinkedHashMap<>();
        for (ProgressStore.Item it : store.items(run.moveId())) {
            byService.computeIfAbsent(it.service(), k -> new ArrayList<>()).add(it);
        }
        for (var e : byService.entrySet()) {
            Cells.Service svc = service(e.getKey());
            for (ProgressStore.Item it : e.getValue()) {
                if ("PENDING".equals(it.phase())) {
                    copyTable(ctx, svc, it, tenant);
                }
            }
            retryDeferred(ctx, svc, tenant);
        }
        if (!reload(run.moveId()).docsDone()) {
            copyDocuments(ctx);
        }
    }

    private void plan(Ctx ctx) {
        UUID tenant = ctx.run().tenantId();
        List<ProgressStore.Item> items = new ArrayList<>();
        for (Cells.Service svc : cells.services()) {
            JsonNode src = http.postOk(ctx.source().url(svc, MOVE + "tables"), null, tenant, null);
            JsonNode dst = http.postOk(ctx.target().url(svc, MOVE + "tables"), null, tenant, null);
            String sv = src.path("schema_version").asString(null);
            String tv = dst.path("schema_version").asString(null);
            if (sv != null && tv != null && !sv.equals(tv)) {
                throw new MoverHttp.PermanentException(svc.name() + ": schema version " + sv + " in " + ctx.source().code()
                        + " but " + tv + " in " + ctx.target().code() + " (deploy the same release to both cells first)");
            }
            Map<String, Long> targetRows = new TreeMap<>();
            dst.path("tables").forEach(t -> targetRows.put(t.path("table_name").asString(), t.path("rows").asLong()));
            int ord = 0;
            for (JsonNode t : src.path("tables")) {
                String table = t.path("table_name").asString();
                if (!targetRows.containsKey(table)) {
                    throw new MoverHttp.PermanentException(svc.name() + ": table " + table + " missing in " + ctx.target().code());
                }
                if (targetRows.get(table) > 0) {
                    throw new MoverHttp.PermanentException(svc.name() + "." + table + " already holds " + targetRows.get(table)
                            + " rows of tenant " + tenant + " in " + ctx.target().code()
                            + " (left over from an earlier move?): clean the target before moving");
                }
                items.add(new ProgressStore.Item(svc.name(), table, ord++, "PENDING", null, 0, null, null, null, null, null));
            }
        }
        store.plan(ctx.run().moveId(), items);
        log.info("Tenant move {}: planned {} tables over {} services", ctx.run().moveId(), items.size(), cells.services().size());
    }

    private void copyTable(Ctx ctx, Cells.Service svc, ProgressStore.Item it, UUID tenant) {
        UUID moveId = ctx.run().moveId();
        UUID after = it.lastId();
        ArrayNode deferred = it.deferred() instanceof ArrayNode a ? a.deepCopy() : json.createArrayNode();
        while (true) {
            ObjectNode req = json.createObjectNode();
            req.put("table_name", it.table());
            req.put("limit", props.pageSize());
            if (after != null) {
                req.put("after_id", after.toString());
            }
            JsonNode page = http.postOk(ctx.source().url(svc, MOVE + "extract"), req, tenant, null);
            ArrayNode records = rewrite(ctx, svc, it.table(), page.path("records"));
            long inserted = 0;
            if (!records.isEmpty()) {
                rows.acquire(records.size());
                JsonNode res = importRows(ctx, svc, it.table(), records, tenant);
                inserted = res.path("inserted").asLong();
                for (JsonNode id : res.path("deferred")) {
                    for (JsonNode r : records) {
                        if (id.asString().equals(r.path("id").asString())) {
                            deferred.add(r);
                        }
                    }
                }
                after = UUID.fromString(records.get(records.size() - 1).path("id").asString());
            }
            store.copied(moveId, svc.name(), it.table(), after, inserted, deferred);
            count("meridian.mover.rows.copied", inserted, "service", svc.name());
            heartbeat(moveId);
            if (!page.path("has_more").asBoolean(false)) {
                break;
            }
        }
        store.phase(moveId, svc.name(), it.table(), "COPIED");
    }

    private JsonNode importRows(Ctx ctx, Cells.Service svc, String table, ArrayNode records, UUID tenant) {
        ObjectNode body = json.createObjectNode();
        body.put("table_name", table);
        body.set("records", records);
        return http.postOk(ctx.target().url(svc, MOVE + "import"), body, tenant, null);
    }

    /** Rows that waited for a parent (cycles within a service), retried until no progress. */
    private void retryDeferred(Ctx ctx, Cells.Service svc, UUID tenant) {
        UUID moveId = ctx.run().moveId();
        boolean progress = true;
        while (progress) {
            progress = false;
            List<ProgressStore.Item> pending = store.items(moveId).stream()
                    .filter(i -> i.service().equals(svc.name()) && i.deferred() != null && !i.deferred().isEmpty()).toList();
            if (pending.isEmpty()) {
                return;
            }
            for (ProgressStore.Item it : pending) {
                ArrayNode records = (ArrayNode) it.deferred();
                JsonNode res = importRows(ctx, svc, it.table(), records, tenant);
                ArrayNode still = json.createArrayNode();
                for (JsonNode id : res.path("deferred")) {
                    for (JsonNode r : records) {
                        if (id.asString().equals(r.path("id").asString())) {
                            still.add(r);
                        }
                    }
                }
                long inserted = res.path("inserted").asLong();
                store.copied(moveId, svc.name(), it.table(), null, inserted, still);
                if (still.size() < records.size()) {
                    progress = true;
                }
            }
        }
        List<String> stuck = store.items(moveId).stream()
                .filter(i -> i.service().equals(svc.name()) && i.deferred() != null && !i.deferred().isEmpty())
                .map(i -> i.table() + "(" + i.deferred().size() + ")").toList();
        throw new MoverHttp.PermanentException(svc.name() + ": rows whose parent rows never arrived: " + stuck);
    }

    private ArrayNode rewrite(Ctx ctx, Cells.Service svc, String table, JsonNode records) {
        ArrayNode out = json.createArrayNode();
        for (JsonNode r : records) {
            ObjectNode row = (ObjectNode) r.deepCopy();
            for (MoverProperties.Rewrite rw : ctx.rewrites()) {
                if (rw.service().equals(svc.name()) && rw.table().equals(table) && row.hasNonNull(rw.column())) {
                    String v = row.get(rw.column()).asString();
                    if (v.startsWith(rw.from())) {
                        row.put(rw.column(), rw.to() + v.substring(rw.from().length()));
                    }
                }
            }
            out.add(row);
        }
        return out;
    }

    private void copyDocuments(Ctx ctx) {
        UUID moveId = ctx.run().moveId();
        if (ctx.sourceDocs() == null || ctx.targetDocs() == null) {
            store.docsProgress(moveId, null, 0, 0, true);
            log.info("Tenant move {}: no object stores configured for {} / {}: documents not copied",
                    moveId, ctx.source().code(), ctx.target().code());
            return;
        }
        String prefix = ctx.run().tenantId() + "/";
        String after = ctx.run().docsLastKey();
        while (true) {
            ObjectStore.Page page = ctx.sourceDocs().list(prefix, after);
            long copied = 0;
            long bytes = 0;
            for (ObjectStore.Obj o : page.objects()) {
                Optional<ObjectStore.Obj> existing = ctx.targetDocs().head(o.key());
                if (existing.isEmpty() || !existing.get().etag().equals(o.etag()) || existing.get().size() != o.size()) {
                    objects.acquire(1);
                    byte[] content = ctx.sourceDocs().get(o.key());
                    ctx.targetDocs().put(o.key(), content);
                    copied++;
                    bytes += content.length;
                }
                after = o.key();
            }
            boolean done = !page.truncated();
            store.docsProgress(moveId, after, copied, bytes, done);
            count("meridian.mover.objects.copied", copied);
            heartbeat(moveId);
            if (done) {
                break;
            }
        }
    }

    // ------------------------------------------------------------------ verification

    void verify(Ctx ctx) {
        UUID moveId = ctx.run().moveId();
        UUID tenant = ctx.run().tenantId();
        List<String> mismatches = new ArrayList<>();
        for (ProgressStore.Item it : store.items(moveId)) {
            if ("VERIFIED".equals(it.phase())) {
                continue;
            }
            Cells.Service svc = service(it.service());
            Digest src = digest(ctx, ctx.source(), svc, it.table(), tenant, true);
            Digest dst = digest(ctx, ctx.target(), svc, it.table(), tenant, false);
            boolean match = src.rows() == dst.rows() && src.sha256().equals(dst.sha256());
            store.verified(moveId, svc.name(), it.table(), src.rows(), dst.rows(), src.sha256(), dst.sha256(), match);
            if (!match) {
                mismatches.add(svc.name() + "." + it.table() + " source " + src.rows() + " rows / " + src.sha256().substring(0, 12)
                        + " target " + dst.rows() + " rows / " + dst.sha256().substring(0, 12));
            }
            heartbeat(moveId);
        }
        if (!mismatches.isEmpty()) {
            throw new MoverHttp.PermanentException("verification failed: " + String.join("; ", mismatches));
        }
        String docsDigest = verifyDocuments(ctx);
        ObjectNode manifest = manifest(ctx, docsDigest);
        String sha = sha256Hex(canonical(manifest));
        long totalRows = 0;
        int tables = 0;
        for (ProgressStore.Item it : store.items(moveId)) {
            totalRows += it.targetRows() == null ? 0 : it.targetRows();
            tables++;
        }
        String checkpoint = copiesRows(ctx.run())
                ? "mover:v1 rows=" + totalRows + " tables=" + tables + " docs=" + manifest.path("documents").path("objects").asLong()
                    + " sha256=" + sha
                : "mover:v1 strategy=NONE shared-database docs=" + manifest.path("documents").path("objects").asLong() + " sha256=" + sha;
        manifest.put("checkpoint", checkpoint);
        manifest.put("verified_at", Clocks.now().toString());
        store.checkpoint(moveId, checkpoint, manifest);
        log.info("Tenant move {} verified: {}", moveId, checkpoint);
    }

    record Digest(long rows, String sha256) {}

    /** Row count and SHA-256 over the canonical JSON of every row of the tenant in id order (source: rewritten). */
    private Digest digest(Ctx ctx, Cells.Cell cell, Cells.Service svc, String table, UUID tenant, boolean source) {
        MessageDigest md = sha256();
        long n = 0;
        UUID after = null;
        while (true) {
            ObjectNode req = json.createObjectNode();
            req.put("table_name", table);
            req.put("limit", props.pageSize());
            if (after != null) {
                req.put("after_id", after.toString());
            }
            JsonNode page = http.postOk(cell.url(svc, MOVE + "extract"), req, tenant, null);
            JsonNode records = source ? rewrite(ctx, svc, table, page.path("records")) : page.path("records");
            for (JsonNode r : records) {
                md.update(canonical(r).getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
                n++;
            }
            rows.acquire(records.size());
            if (!records.isEmpty()) {
                after = UUID.fromString(records.get(records.size() - 1).path("id").asString());
            }
            if (!page.path("has_more").asBoolean(false)) {
                break;
            }
        }
        return new Digest(n, HexFormat.of().formatHex(md.digest()));
    }

    /** Compares the tenant's document listings of both buckets; returns their digest. */
    private String verifyDocuments(Ctx ctx) {
        ProgressStore.Run run = reload(ctx.run().moveId());
        if (run.docsVerified() || ctx.sourceDocs() == null || ctx.targetDocs() == null) {
            return run.docsVerified() ? run.docsDigest() : "none";
        }
        String prefix = run.tenantId() + "/";
        Map<String, String> src = listing(ctx.sourceDocs(), prefix);
        Map<String, String> dst = listing(ctx.targetDocs(), prefix);
        if (!src.equals(dst)) {
            List<String> diff = new ArrayList<>();
            src.forEach((k, v) -> {
                if (!v.equals(dst.get(k))) {
                    diff.add(k);
                }
            });
            dst.keySet().stream().filter(k -> !src.containsKey(k)).forEach(diff::add);
            throw new MoverHttp.PermanentException("document verification failed for " + diff.size() + " objects, e.g. "
                    + diff.stream().limit(5).toList());
        }
        StringBuilder sb = new StringBuilder();
        src.forEach((k, v) -> sb.append(k).append(' ').append(v).append('\n'));
        String d = sha256Hex(sb.toString()) + ":" + src.size();
        store.docsVerified(run.moveId(), d);
        return d;
    }

    private Map<String, String> listing(ObjectStore store, String prefix) {
        Map<String, String> out = new TreeMap<>();
        String after = null;
        while (true) {
            ObjectStore.Page p = store.list(prefix, after);
            for (ObjectStore.Obj o : p.objects()) {
                out.put(o.key(), o.size() + " " + o.etag());
                after = o.key();
            }
            if (!p.truncated() || p.objects().isEmpty()) {
                return out;
            }
        }
    }

    private ObjectNode manifest(Ctx ctx, String docsDigest) {
        ProgressStore.Run run = ctx.run();
        ObjectNode m = json.createObjectNode();
        m.put("move_id", run.moveId().toString());
        m.put("tenant_id", run.tenantId().toString());
        m.put("from_cell", run.fromCell());
        m.put("to_cell", run.toCell());
        m.put("strategy", run.strategy());
        ObjectNode services = m.putObject("services");
        for (ProgressStore.Item it : store.items(run.moveId())) {
            ObjectNode t = (services.has(it.service()) ? (ObjectNode) services.get(it.service()) : services.putObject(it.service()))
                    .putObject(it.table());
            t.put("rows", it.targetRows() == null ? 0 : it.targetRows());
            t.put("sha256", it.targetDigest());
        }
        ProgressStore.Run now = reload(run.moveId());
        ObjectNode docs = m.putObject("documents");
        docs.put("objects", now.docsCopied());
        docs.put("bytes", now.docsBytes());
        docs.put("listing", "none".equals(docsDigest) ? "none" : docsDigest);
        if (!"none".equals(docsDigest) && docsDigest.contains(":")) {
            docs.put("objects", Long.parseLong(docsDigest.substring(docsDigest.indexOf(':') + 1)));
        }
        return m;
    }

    // ------------------------------------------------------------------ confirm

    private void confirm(ProgressStore.Run run) {
        if (!props.confirm()) {
            log.info("Tenant move {} verified; confirm disabled (meridian.mover.confirm=false)", run.moveId());
            store.status(run.moveId(), "COMPLETED", null);
            return;
        }
        if (props.registryUrl() == null || props.registryUrl().isBlank()) {
            throw new MoverHttp.PermanentException("meridian.mover.registry-url is not set: cannot confirm the copy");
        }
        ObjectNode body = json.createObjectNode();
        body.put("checkpoint", run.checkpoint());
        body.put("verified", true);
        UUID operator = props.operatorTenant() == null || props.operatorTenant().isBlank() ? null
                : UUID.fromString(props.operatorTenant());
        MoverHttp.Response r = http.post(props.registryUrl() + "/v1/platform/tenant-moves/" + run.moveId() + "/confirm-copy",
                body, operator, "data-mover:confirm:" + run.moveId());
        if (r.status() >= 200 && r.status() < 300) {
            store.status(run.moveId(), "COMPLETED", null);
            count("meridian.mover.runs", 1, "outcome", "completed");
            log.info("Tenant move {}: copy confirmed to tenant-service at {}", run.moveId(), run.checkpoint());
            return;
        }
        if (r.status() == 409 && "freeze_grace_pending".equals(r.code())) {
            store.release(run.moveId(), "freeze grace pending", Clocks.now().plusSeconds(10));
            return;
        }
        if (r.status() >= 500 || r.status() == 429) {
            throw new MoverHttp.TransientException("confirm-copy answered " + r.status());
        }
        throw new MoverHttp.PermanentException("confirm-copy refused: " + r.status() + " " + r.code() + " "
                + r.body().path("detail").asString(""));
    }

    // ------------------------------------------------------------------ helpers

    private Cells.Service service(String name) {
        return cells.services().stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new MoverHttp.PermanentException("service " + name + " is no longer configured"));
    }

    /** Canonical JSON: object keys sorted, no whitespace; scalars as Jackson writes them. */
    static String canonical(JsonNode n) {
        StringBuilder sb = new StringBuilder();
        canonical(n, sb);
        return sb.toString();
    }

    private static void canonical(JsonNode n, StringBuilder sb) {
        if (n.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            n.properties().forEach(e -> sorted.put(e.getKey(), e.getValue()));
            sb.append('{');
            boolean first = true;
            for (var e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(tools.jackson.databind.node.JsonNodeFactory.instance.stringNode(e.getKey()).toString()).append(':');
                canonical(e.getValue(), sb);
            }
            sb.append('}');
        } else if (n.isArray()) {
            sb.append('[');
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                canonical(n.get(i), sb);
            }
            sb.append(']');
        } else {
            sb.append(n.toString());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(String s) {
        return HexFormat.of().formatHex(sha256().digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
