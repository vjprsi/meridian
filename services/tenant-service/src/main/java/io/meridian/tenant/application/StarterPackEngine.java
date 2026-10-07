package io.meridian.tenant.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.tenant.domain.PackTemplates;
import io.meridian.tenant.domain.StarterPack;
import io.meridian.tenant.domain.StarterPack.Action;
import io.meridian.tenant.domain.StarterPack.Item;
import io.meridian.tenant.infrastructure.StarterPackStore;
import io.meridian.web.client.ServiceClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Executes one starter pack run for the tenant bound in {@link TenantContext} (the caller binds the target tenant and
 * the acting principal; {@link ServiceClient} sends them as {@code X-Tenant-Id} / {@code X-User}, with the service
 * token when no user request is in scope).
 *
 * <p>Per item: an item already applied with the same definition is skipped (no call); an unconfirmed create is
 * replayed with the body and Idempotency-Key recorded before the first attempt; a pre-existing resource found by
 * the item's lookup is recorded as EXISTING and never changed; a pack-owned item whose definition changed in a newer
 * pack version is upgraded as the item says (PATCH, new version, or reported). Follow-up actions are status driven:
 * an action is skipped when the resource is already past it, waits when the resource is not yet in the required
 * status (e.g. publish before approval), and manual actions (a checker's activation) are never performed by the
 * pack. Maker-checker submissions open approval requests that are listed in the result; nothing is approved here.
 * The first failure stops the run; everything done so far is recorded, so a re-run resumes where it stopped.
 */
@Component
public class StarterPackEngine {
    private static final Logger log = LoggerFactory.getLogger(StarterPackEngine.class);
    static final String WORKFLOW = "workflow-service";

    private final ServiceClient client;
    private final StarterPackStore store;
    private final TransactionTemplate tx;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public StarterPackEngine(ServiceClient client, StarterPackStore store, TransactionTemplate tx, OutboxWriter outbox,
                             JsonMapper json) {
        this.client = client;
        this.store = store;
        this.tx = tx;
        this.outbox = outbox;
        this.json = json;
    }

    /** Runs the pack and finalises the run row (status, counters, per-step results, event). */
    public ObjectNode execute(StarterPack pack, Map<String, JsonNode> params, UUID runId, boolean dryRun, String today) {
        Execution x = new Execution(pack, params, runId, dryRun, today);
        x.registry.putAll(tx.execute(s -> store.items(pack.code())));
        try {
            for (StarterPack.Step step : pack.steps()) {
                ObjectNode stepResult = x.results.addObject();
                stepResult.put("step", step.code());
                stepResult.put("title", step.title());
                ArrayNode items = stepResult.putArray("items");
                for (Item item : step.items()) {
                    process(x, item, null, items);
                }
                stepResult.put("status", stepStatus(items));
                if (!dryRun) {
                    progress(x);
                }
            }
        } catch (RuntimeException e) {
            log.error("starter pack {} run {} aborted", pack.latestKey(), runId, e);
            x.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return finish(x);
    }

    // ------------------------------------------------------------------ items

    private void process(Execution x, Item item, Parent parent, ArrayNode out) {
        ObjectNode r = out.addObject();
        r.put("key", item.key());
        r.put("title", item.title());
        r.put("service", item.service());
        r.put("path", item.path());
        if (x.stopped) {
            outcome(x, r, "NOT_RUN", null, "an earlier item failed; re-run or resume to continue");
            item.children().forEach(c -> process(x, c, null, out));
            return;
        }
        if (parent != null && parent.skipOutcome() != null) {
            outcome(x, r, parent.skipOutcome(), parent.skipOutcome(), parent.skipMessage());
            item.children().forEach(c -> process(x, c, parent, out));
            return;
        }
        ObjectNode row = x.registry.get(item.key());
        String checksum = PackTemplates.checksum(item, x.params);
        try {
            switch (item.kind()) {
                case CALL -> call(x, item, row, checksum, r);
                case PATCH_EXISTING -> patchExisting(x, item, row, checksum, r, parent);
                case CREATE -> create(x, item, row, checksum, r, parent, out);
            }
        } catch (PackTemplates.Unresolved e) {
            blocked(x, item, row, checksum, r, e.getMessage());
            item.children().forEach(c -> process(x, c, new Parent(null, "BLOCKED", "parent item " + item.key() + " is blocked"), out));
        } catch (ApiException e) {
            failed(x, item, row, checksum, r, e.code() + ": " + e.getMessage());
            item.children().forEach(c -> process(x, c, null, out));
        }
    }

    private void create(Execution x, Item item, ObjectNode row, String checksum, ObjectNode r, Parent parent, ArrayNode out) {
        PackTemplates.Context ctx = x.context(parent == null ? null : parent.id());
        String resourceId = text(row, "resource_id");
        String outcome;
        String message = null;
        if (row != null && "EXISTING".equals(text(row, "status"))) {
            r.put("resource_id", resourceId);
            outcome(x, r, "EXISTING", "EXISTING", "found before the pack created it; never changed by the pack");
            children(x, item, resourceId, false, out);
            return;
        }
        if (resourceId == null) {
            if (row == null && item.lookup() != null && !x.dryRun) {
                Optional<JsonNode> found = lookup(item, ctx);
                if (found.isPresent()) {
                    String id = found.get().get("id").asString();
                    x.save(item, null, values(x, item, checksum, "EXISTING").put("resource_id", id).put("is_pack_owned", false));
                    r.put("resource_id", id);
                    outcome(x, r, "EXISTING", "EXISTING", "already exists in " + item.service() + "; left unchanged (not pack-owned)");
                    children(x, item, id, false, out);
                    return;
                }
            }
            if (x.dryRun) {
                outcome(x, r, row == null ? "WOULD_CREATE" : "WOULD_RESUME", text(row, "status"),
                        item.lookup() == null ? null : "unless " + item.service() + " already has a match for " + item.lookup().match());
                children(x, item, null, true, out);
                return;
            }
            JsonNode body;
            String key;
            if (row != null && checksum.equals(text(row, "checksum")) && row.hasNonNull("request_body")
                    && row.hasNonNull("idempotency_key")) {
                body = row.get("request_body");              // replay the unconfirmed attempt verbatim
                key = row.get("idempotency_key").asString();
            } else {
                body = PackTemplates.render(item.body(), ctx);
                key = idempotencyKey(x, item, "create", checksum);
            }
            row = x.save(item, row, values(x, item, checksum, "PENDING").set("request_body", body).put("idempotency_key", key));
            JsonNode created = client.post(item.service(), item.path(), body, key);
            resourceId = created.path("id").asString(null);
            if (resourceId == null) {
                throw new ApiException(502, "upstream_error", item.service() + " returned no id for " + item.path());
            }
            ObjectNode done = values(x, item, checksum, "DONE").put("resource_id", resourceId);
            done.putNull("request_body");
            done.putNull("idempotency_key");
            done.putNull("last_error");
            row = x.save(item, row, done);
            outcome = "CREATED";
        } else if (!checksum.equals(text(row, "checksum"))) {
            switch (item.update()) {
                case PATCH -> {
                    if (x.dryRun) {
                        outcome = "WOULD_UPDATE";
                        break;
                    }
                    String path = item.resourcePath(resourceId);
                    JsonNode current = client.get(item.service(), path);
                    ObjectNode body = (ObjectNode) PackTemplates.render(item.body(), ctx);
                    item.updateExclude().forEach(body::remove);
                    client.patch(item.service(), path, body, current.path("row_version").asString(null));
                    row = x.save(item, row, values(x, item, checksum, "DONE"));
                    outcome = "UPDATED";
                }
                case RECREATE -> {
                    if (x.dryRun) {
                        outcome = "WOULD_UPDATE";
                        break;
                    }
                    JsonNode body = PackTemplates.render(item.body(), ctx);
                    String key = idempotencyKey(x, item, "create", checksum);
                    ObjectNode pending = values(x, item, checksum, "PENDING").set("request_body", body).put("idempotency_key", key);
                    pending.putNull("resource_id");
                    row = x.save(item, row, pending);
                    resourceId = client.post(item.service(), item.path(), body, key).path("id").asString(null);
                    ObjectNode done = values(x, item, checksum, "DONE").put("resource_id", resourceId);
                    done.putNull("request_body");
                    done.putNull("idempotency_key");
                    row = x.save(item, row, done);
                    outcome = "UPDATED";
                    message = "new version created for pack " + x.pack.version();
                }
                default -> {
                    outcome = "UPGRADE_SKIPPED";
                    message = "definition changed in pack " + x.pack.version() + " (applied: " + text(row, "pack_version")
                            + "); " + item.service() + " does not allow an automatic update, apply it manually";
                }
            }
        } else {
            outcome = "UNCHANGED";
        }
        r.put("resource_id", resourceId);
        children(x, item, resourceId, false, out);
        if (x.dryRun || x.stopped) {
            outcome(x, r, outcome, text(row, "status"), message);
            return;
        }
        settle(x, item, row, resourceId, checksum, ctx, r, outcome, message);
    }

    private void children(Execution x, Item item, String id, boolean dry, ArrayNode out) {
        ObjectNode row = x.registry.get(item.key());
        boolean existing = row != null && "EXISTING".equals(text(row, "status"));
        boolean skip = existing && !item.adoptChildren();
        for (Item c : item.children()) {
            process(x, c, skip ? new Parent(null, "SKIPPED", "parent " + item.key()
                    + " already existed (not pack-owned); its children are left to the tenant") : new Parent(dry ? null : id, null, null), out);
        }
    }

    /** Runs the follow-up actions that are due and derives the item state from the resource status. */
    private void settle(Execution x, Item item, ObjectNode row, String id, String checksum, PackTemplates.Context ctx,
                        ObjectNode r, String outcome, String message) {
        if (item.actions().isEmpty() && item.readyStatus().isEmpty()) {
            outcome(x, r, outcome, "DONE", message);
            return;
        }
        String status = status(item, id);
        String manual = null;
        List<String> waitingFor = List.of();
        Set<String> approvals = new LinkedHashSet<>();
        for (Action a : item.actions()) {
            if (a.skipStatus().contains(status)) {
                continue;
            }
            if (!a.requiresStatus().isEmpty() && !a.requiresStatus().contains(status)) {
                break;
            }
            waitingFor = a.after().stream().filter(k -> !"DONE".equals(text(x.registry.get(k), "status"))).toList();
            if (!waitingFor.isEmpty()) {
                break;
            }
            String path = item.path() + a.path().replace("{id}", id);
            if (a.manual()) {
                manual = "POST " + path + " by a user other than " + TenantContext.principal()
                        + (a.description().isBlank() ? "" : " (" + a.description() + ")");
                break;
            }
            JsonNode res = client.post(item.service(), path, PackTemplates.render(a.body(), ctx),
                    idempotencyKey(x, item, a.name(), checksum + ":" + id));
            if (a.approval() != null && res.hasNonNull("resource_type") && res.hasNonNull("id")) {
                approvals.add(res.get("id").asString());
            }
            status = status(item, id);
        }
        ObjectNode v = values(x, item, checksum(row, checksum), null);
        v.putNull("last_error");
        String state;
        if (item.readyStatus().contains(status)) {
            state = "DONE";
            approvals.clear();
            v.putNull("pending_action");
        } else if (!waitingFor.isEmpty()) {
            state = "AWAITING_APPROVAL";
            message = (message == null ? "" : message + "; ") + "waits for " + String.join(", ", waitingFor);
        } else if (manual != null) {
            state = "AWAITING_ACTIVATION";
            v.put("pending_action", manual.length() > 255 ? manual.substring(0, 255) : manual);
        } else if (item.pendingStatus().contains(status)) {
            state = "AWAITING_APPROVAL";
            for (Action a : item.actions()) {
                if (a.approval() != null) {
                    approvals.addAll(pendingApprovals(a.approval(), id));
                }
            }
        } else {
            state = "BLOCKED";
            message = (message == null ? "" : message + "; ") + item.service() + " reports status " + status
                    + "; the pack does not act on it (expected " + String.join("/", item.readyStatus()) + ")";
        }
        v.put("status", state);
        ArrayNode ids = v.putArray("approval_request_ids");
        approvals.forEach(ids::add);
        x.save(item, row, v);
        r.put("resource_status", status);
        if (!approvals.isEmpty()) {
            ArrayNode a = r.putArray("approval_request_ids");
            approvals.forEach(a::add);
            x.approvals.addAll(approvals);
        }
        if (manual != null) {
            r.put("pending_action", manual);
        }
        outcome(x, r, outcome, state, message);
    }

    private void call(Execution x, Item item, ObjectNode row, String checksum, ObjectNode r) {
        if (row != null && "DONE".equals(text(row, "status")) && checksum.equals(text(row, "checksum"))) {
            outcome(x, r, "UNCHANGED", "DONE", null);
            return;
        }
        if (x.dryRun) {
            outcome(x, r, row == null ? "WOULD_CREATE" : "WOULD_UPDATE", text(row, "status"), null);
            return;
        }
        // first successful call (also after an unconfirmed or failed first attempt) vs. re-applying a changed definition
        boolean first = row == null || "PENDING".equals(text(row, "status"))
                || "FAILED".equals(text(row, "status")) && checksum.equals(text(row, "checksum"));
        JsonNode body = PackTemplates.render(item.body(), x.context(null));
        String key = idempotencyKey(x, item, "call", checksum);
        row = x.save(item, row, values(x, item, checksum, "PENDING").put("idempotency_key", key));
        JsonNode res = client.send(item.service(), item.method(), item.path(), body, key);
        ObjectNode done = values(x, item, checksum, "DONE");
        done.putNull("idempotency_key");
        done.putNull("last_error");
        x.save(item, row, done);
        r.set("response", summary(res));
        outcome(x, r, first ? "CREATED" : "UPDATED", "DONE", null);
    }

    private void patchExisting(Execution x, Item item, ObjectNode row, String checksum, ObjectNode r, Parent parent) {
        if (row != null && "DONE".equals(text(row, "status")) && checksum.equals(text(row, "checksum"))) {
            r.put("resource_id", text(row, "resource_id"));
            outcome(x, r, "UNCHANGED", "DONE", null);
            return;
        }
        if (x.dryRun) {
            outcome(x, r, "WOULD_UPDATE", text(row, "status"), null);
            return;
        }
        PackTemplates.Context ctx = x.context(parent == null ? null : parent.id());
        JsonNode body = PackTemplates.render(item.body(), ctx);
        JsonNode target = lookup(item, ctx).orElseThrow(() -> ApiException.unprocessable("starter_pack_target_missing",
                "No " + item.path() + " matches " + PackTemplates.render(item.lookup().match(), ctx)));
        String id = target.get("id").asString();
        client.patch(item.service(), item.resourcePath(id), body, target.path("row_version").asString(null));
        x.save(item, row, values(x, item, checksum, "DONE").put("resource_id", id).putNull("last_error"));
        r.put("resource_id", id);
        outcome(x, r, "UPDATED", "DONE", null);
    }

    private void failed(Execution x, Item item, ObjectNode row, String checksum, ObjectNode r, String error) {
        x.fail(item.key() + ": " + error);
        if (!x.dryRun) {
            ObjectNode v = values(x, item, checksum(row, checksum), "FAILED").put("last_error", error);
            x.save(item, x.registry.get(item.key()), v);
        }
        outcome(x, r, "FAILED", "FAILED", error);
    }

    private void blocked(Execution x, Item item, ObjectNode row, String checksum, ObjectNode r, String why) {
        if (!x.dryRun) {
            x.save(item, row, values(x, item, checksum(row, checksum), "BLOCKED").put("last_error", why));
        }
        outcome(x, r, "BLOCKED", "BLOCKED", why);
    }

    // ------------------------------------------------------------------ remote helpers

    private Optional<JsonNode> lookup(Item item, PackTemplates.Context ctx) {
        JsonNode query = PackTemplates.render(item.lookup().query(), ctx);
        JsonNode match = PackTemplates.render(item.lookup().match(), ctx);
        StringBuilder path = new StringBuilder(item.path()).append("?limit=200");
        query.properties().forEach(e -> path.append('&').append(e.getKey()).append('=')
                .append(URLEncoder.encode(e.getValue().isString() ? e.getValue().asString() : e.getValue().toString(),
                        StandardCharsets.UTF_8)));
        JsonNode page = client.get(item.service(), path.toString());
        for (JsonNode row : page.path("data")) {
            boolean all = true;
            for (Map.Entry<String, JsonNode> m : match.properties()) {
                JsonNode v = row.get(m.getKey());
                boolean eq = m.getValue().isNull() ? (v == null || v.isNull())
                        : v != null && !v.isNull() && (v.equals(m.getValue()) || v.asString("").equals(m.getValue().asString("\u0000")));
                all &= eq;
            }
            if (all) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    private String status(Item item, String id) {
        return client.get(item.service(), item.resourcePath(id)).path(item.statusField()).asString("");
    }

    private List<String> pendingApprovals(String resourceType, String id) {
        List<String> out = new ArrayList<>();
        try {
            JsonNode page = client.get(WORKFLOW, "/v1/approval-requests?status=PENDING&limit=50&resource_type="
                    + URLEncoder.encode(resourceType, StandardCharsets.UTF_8) + "&resource_id=" + id);
            page.path("data").forEach(a -> out.add(a.get("id").asString()));
        } catch (ApiException e) {
            log.warn("cannot list approval requests for {}/{}: {}", resourceType, id, e.getMessage());
        }
        return out;
    }

    private JsonNode summary(JsonNode res) {
        ObjectNode o = json.createObjectNode();
        res.properties().forEach(e -> {
            if (e.getValue().isValueNode()) {
                o.set(e.getKey(), e.getValue());
            }
        });
        return o;
    }

    /** Deterministic key per tenant, pack, item, operation and definition: re-runs replay instead of duplicating. */
    static String idempotencyKey(Execution x, Item item, String op, String discriminator) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((TenantContext.require() + "|" + x.pack.code() + "|"
                    + item.key() + "|" + op + "|" + discriminator).getBytes(StandardCharsets.UTF_8));
            return "sp-" + x.pack.code().toLowerCase(java.util.Locale.ROOT) + "-" + HexFormat.of().formatHex(h).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ bookkeeping

    private ObjectNode values(Execution x, Item item, String checksum, String status) {
        ObjectNode v = json.createObjectNode();
        v.put("pack_code", x.pack.code());
        v.put("item_key", item.key());
        v.put("step_code", item.stepCode());
        v.put("target_service", item.service());
        v.put("resource_path", item.path());
        v.put("checksum", checksum);
        v.put("pack_version", x.pack.version());
        v.put("last_run_id", x.runId.toString());
        if (status != null) {
            v.put("status", status);
        }
        return v;
    }

    /** Keeps the applied checksum of an item whose newer definition was not applied (UPGRADE_SKIPPED). */
    private static String checksum(ObjectNode row, String current) {
        return row != null && row.hasNonNull("checksum") ? row.get("checksum").asString() : current;
    }

    private void outcome(Execution x, ObjectNode r, String outcome, String state, String message) {
        r.put("outcome", outcome);
        if (state != null) {
            r.put("status", state);
        }
        if (message != null) {
            r.put("message", message);
        }
        x.count(outcome, state);
    }

    private static String stepStatus(ArrayNode items) {
        Set<String> s = new LinkedHashSet<>();
        items.forEach(i -> {
            s.add(i.path("outcome").asString());
            s.add(i.path("status").asString(""));
        });
        if (s.contains("FAILED")) {
            return "FAILED";
        }
        if (s.contains("BLOCKED")) {
            return "NEEDS_ATTENTION";
        }
        if (s.contains("NOT_RUN")) {
            return "NOT_RUN";
        }
        if (s.contains("AWAITING_APPROVAL") || s.contains("AWAITING_ACTIVATION")) {
            return "AWAITING_APPROVAL";
        }
        return "DONE";
    }

    private void progress(Execution x) {
        ObjectNode v = json.createObjectNode();
        v.set("results", x.results);
        x.putCounts(v);
        tx.executeWithoutResult(s -> store.updateRun(x.runId, v));
    }

    private ObjectNode finish(Execution x) {
        ObjectNode v = json.createObjectNode();
        String status = x.stopped ? "FAILED" : x.blocked > 0 ? "NEEDS_ATTENTION"
                : x.awaiting > 0 ? "AWAITING_APPROVAL" : "COMPLETED";
        v.put("status", status);
        v.put("finished_at", io.meridian.common.time.Clocks.now().toString());
        v.set("results", x.results);
        x.putCounts(v);
        ArrayNode ids = v.putArray("approval_request_ids");
        x.approvals.forEach(ids::add);
        if (x.error != null) {
            v.put("error", x.error);
        }
        return tx.execute(s -> {
            ObjectNode row = store.updateRun(x.runId, v);
            if (!x.dryRun) {
                outbox.publish("platform.starter_pack_run.completed", StarterPackStore.RUN, x.runId, row);
            }
            return row;
        });
    }

    /** The parent's resource id, or why the children are not processed (SKIPPED / BLOCKED). */
    private record Parent(String id, String skipOutcome, String skipMessage) {}

    /** State of one run. */
    final class Execution {
        final StarterPack pack;
        final Map<String, JsonNode> params;
        final UUID runId;
        final boolean dryRun;
        final String today;
        final Map<String, ObjectNode> registry = new java.util.HashMap<>();
        final ArrayNode results = json.createArrayNode();
        final Set<String> approvals = new LinkedHashSet<>();
        int created;
        int updated;
        int unchanged;
        int existing;
        int awaiting;
        int failed;
        int blocked;
        boolean stopped;
        String error;

        Execution(StarterPack pack, Map<String, JsonNode> params, UUID runId, boolean dryRun, String today) {
            this.pack = pack;
            this.params = params;
            this.runId = runId;
            this.dryRun = dryRun;
            this.today = today;
        }

        PackTemplates.Context context(String parentId) {
            return new PackTemplates.Context(params, key -> {
                ObjectNode row = registry.get(key);
                return row == null ? null : text(row, "resource_id");
            }, parentId, today, TenantContext.require().toString(), pack.code(), pack.version());
        }

        ObjectNode save(Item item, ObjectNode row, ObjectNode values) {
            ObjectNode current = row == null ? registry.get(item.key()) : row;
            ObjectNode saved = tx.execute(s -> store.saveItem(current, values));
            registry.put(item.key(), saved);
            return saved;
        }

        void fail(String message) {
            stopped = true;
            if (error == null) {
                error = message;
            }
        }

        void count(String outcome, String state) {
            switch (outcome) {
                case "CREATED", "WOULD_CREATE" -> created++;
                case "UPDATED", "WOULD_UPDATE" -> updated++;
                case "UNCHANGED", "UPGRADE_SKIPPED" -> unchanged++;
                case "EXISTING", "SKIPPED" -> existing++;
                case "FAILED" -> failed++;
                case "BLOCKED" -> {
                    failed++;
                    blocked++;
                }
                default -> {
                }
            }
            if ("BLOCKED".equals(state) && !"BLOCKED".equals(outcome)) {
                failed++;
                blocked++;
            }
            if ("AWAITING_APPROVAL".equals(state) || "AWAITING_ACTIVATION".equals(state)) {
                awaiting++;
            }
        }

        void putCounts(ObjectNode v) {
            v.put("created_count", created);
            v.put("updated_count", updated);
            v.put("unchanged_count", unchanged);
            v.put("existing_count", existing);
            v.put("awaiting_approval_count", awaiting);
            v.put("failed_count", failed);
        }
    }

    static String text(JsonNode row, String field) {
        return row == null || !row.hasNonNull(field) ? null : row.get(field).asString();
    }
}
