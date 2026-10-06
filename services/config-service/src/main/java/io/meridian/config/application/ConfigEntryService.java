package io.meridian.config.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.config.domain.ConfigLifecycle;
import io.meridian.config.domain.ConfigLifecycle.Status;
import io.meridian.config.domain.JsonDiff;
import io.meridian.config.domain.JsonSchemaValidator;
import io.meridian.config.infrastructure.ConfigStore;
import io.meridian.config.infrastructure.WorkflowGateway;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Versioned, approval-gated configuration. Each change is a new version of (namespace, config_key):
 * DRAFT -> submit (approval request in workflow-service) -> PENDING_APPROVAL -> activate (checker, after approval)
 * -> ACTIVE; the previous ACTIVE version becomes SUPERSEDED. Rollback re-activates a previously approved value
 * as a new version. Every version change is recorded in the append-only config_change history.
 */
@Service
public class ConfigEntryService {
    static final String BUNDLE_FORMAT = "meridian.config.bundle/v1";
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_.-]{0,127}$");
    private static final Set<String> GRANTED = Set.of("APPROVED", "EXECUTED");
    private static final Set<String> REFUSED = Set.of("REJECTED", "CANCELLED", "EXPIRED", "EXECUTION_FAILED");

    private final CrudEngine crud;
    private final ConfigStore store;
    private final WorkflowGateway workflow;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public ConfigEntryService(CrudEngine crud, ConfigStore store, WorkflowGateway workflow, OutboxWriter outbox,
                              TransactionTemplate tx, JsonMapper json) {
        this.crud = crud;
        this.store = store;
        this.workflow = workflow;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
    }

    // ------------------------------------------------------------------ create

    /** Creates a new DRAFT version (version = latest + 1) after validating the value against its schema. */
    @Transactional
    public String create(Object body) {
        ObjectNode values = crud.toColumns("config_entry", body);
        String namespace = text(values, "namespace");
        String key = text(values, "config_key");
        requireName("namespace", namespace);
        requireName("config_key", key);
        JsonNode value = values.get("value");
        JsonSchemaValidator.require(values.get("value_schema"), value);
        return insertDraft(namespace, key, value, values, null);
    }

    private String insertDraft(String namespace, String key, JsonNode value, ObjectNode values, String changeReason) {
        store.lockKey(namespace, key);
        int version = store.maxVersion(namespace, key) + 1;
        values.remove(List.of("approval_request_id", "status", "version"));
        values.put("id", Ids.newId().toString());
        values.put("version", version);
        values.put("status", Status.DRAFT.name());
        if (changeReason != null && !values.has("change_reason")) {
            values.put("change_reason", changeReason);
        }
        String row = crud.insert("config_entry", values);
        JsonNode active = store.active(namespace, key).map(json::readTree).orElse(null);
        change(json.readTree(row), active == null ? null : active.get("version").asInt(), "CREATED",
                JsonDiff.diff(json, active == null ? null : active.get("value"), value), null);
        return row;
    }

    // ------------------------------------------------------------------ submit

    /** Sends a DRAFT for checker approval in workflow-service and marks it PENDING_APPROVAL. */
    public JsonNode submit(UUID id, String changeReason, String idempotencyKey) {
        if (changeReason == null || changeReason.isBlank()) {
            throw ApiException.badRequest("validation_failed", "change_reason is required");
        }
        JsonNode entry = json.readTree(store.entry(id, false).orElseThrow(() -> ApiException.notFound("config entry", id)));
        ConfigLifecycle.require(entry.get("status").asString(), Status.PENDING_APPROVAL);
        JsonSchemaValidator.require(entry.get("value_schema"), entry.get("value"));
        String namespace = entry.get("namespace").asString();
        String key = entry.get("config_key").asString();
        JsonNode active = store.active(namespace, key).map(json::readTree).orElse(null);

        ObjectNode req = json.createObjectNode();
        req.put("resource_type", "config_entry");
        req.put("resource_id", id.toString());
        req.put("action", "activate");
        req.put("title", "Activate " + namespace + "." + key + " v" + entry.get("version").asInt());
        req.put("summary", changeReason);
        req.put("priority", "NORMAL");
        ObjectNode payload = req.putObject("payload");
        payload.put("service", "config-service");
        payload.put("operation", "configEntriesActivate");
        payload.put("config_entry_id", id.toString());
        payload.put("namespace", namespace);
        payload.put("config_key", key);
        payload.put("version", entry.get("version").asInt());
        payload.set("value", entry.get("value"));
        req.set("diff", diffDocument(JsonDiff.diff(json, active == null ? null : active.get("value"), entry.get("value"))));
        JsonNode approval = workflow.requestApproval(req, "config-service:" + idempotencyKey);
        String approvalId = approval == null ? null : approval.path("id").asString(null);
        if (approvalId == null) {
            throw new ApiException(502, "upstream_error", "workflow-service returned no approval request id");
        }

        tx.executeWithoutResult(s -> {
            JsonNode locked = json.readTree(store.entry(id, true).orElseThrow(() -> ApiException.notFound("config entry", id)));
            ConfigLifecycle.require(locked.get("status").asString(), Status.PENDING_APPROVAL);
            ObjectNode upd = json.createObjectNode();
            upd.put("status", Status.PENDING_APPROVAL.name());
            upd.put("approval_request_id", approvalId);
            upd.put("change_reason", changeReason);
            crud.updateRow("config_entry", java.util.Map.of("id", id), upd, null);
        });
        return approval;
    }

    // ------------------------------------------------------------------ activate

    /**
     * Activates a PENDING_APPROVAL version once workflow-service reports the approval as granted. The activating
     * principal (checker) must differ from the maker who created or submitted the version.
     */
    public String activate(UUID id) {
        JsonNode entry = json.readTree(store.entry(id, false).orElseThrow(() -> ApiException.notFound("config entry", id)));
        ConfigLifecycle.require(entry.get("status").asString(), Status.ACTIVE);
        String principal = TenantContext.principal();
        if (principal.equals(entry.path("created_by").asString(null)) || principal.equals(entry.path("updated_by").asString(null))) {
            throw ApiException.unprocessable("four_eyes_violation",
                    "The maker of a configuration change cannot activate it; another user must act as checker");
        }
        String approvalId = entry.path("approval_request_id").asString(null);
        if (approvalId == null) {
            throw ApiException.conflict("approval_missing", "Config entry " + id + " has no approval request");
        }
        JsonNode approval = workflow.approval(UUID.fromString(approvalId));
        if (approval == null) {
            throw new ApiException(502, "upstream_error", "workflow-service returned no approval request " + approvalId);
        }
        String approvalStatus = approval.path("status").asString("");
        if (REFUSED.contains(approvalStatus)) {
            throw ApiException.conflict("approval_not_granted", "Approval request " + approvalId + " is " + approvalStatus);
        }
        if (!GRANTED.contains(approvalStatus)) {
            throw ApiException.conflict("approval_pending", "Approval request " + approvalId + " is still " + approvalStatus);
        }
        if (principal.equals(approval.path("maker").asString(null))) {
            throw ApiException.unprocessable("four_eyes_violation", "The maker of the approval request cannot activate it");
        }
        return tx.execute(s -> {
            String namespace = entry.get("namespace").asString();
            String key = entry.get("config_key").asString();
            store.lockKey(namespace, key);
            JsonNode locked = json.readTree(store.entry(id, true).orElseThrow());
            ConfigLifecycle.require(locked.get("status").asString(), Status.ACTIVE);
            Optional<JsonNode> previous = store.active(namespace, key).map(json::readTree);
            if (previous.isPresent() && previous.get().get("version").asInt() > locked.get("version").asInt()) {
                throw ApiException.conflict("stale_version", "A newer version v" + previous.get().get("version").asInt()
                        + " is already active; create a new draft instead");
            }
            previous.ifPresent(p -> setStatus(p, Status.SUPERSEDED));
            ObjectNode upd = json.createObjectNode();
            upd.put("status", Status.ACTIVE.name());
            upd.put("effective_from", locked.hasNonNull("effective_from") ? locked.get("effective_from").asString()
                    : OffsetDateTime.now().toString());
            String row = crud.updateRow("config_entry", java.util.Map.of("id", id), upd, null).orElseThrow();
            JsonNode rowNode = json.readTree(row);
            change(rowNode, previous.map(p -> p.get("version").asInt()).orElse(null), "ACTIVATED",
                    JsonDiff.diff(json, previous.map(p -> p.get("value")).orElse(null), rowNode.get("value")), principal);
            outbox.publish("platform.config_entry.activated", "config_entry", id, rowNode);
            return row;
        });
    }

    // ------------------------------------------------------------------ rollback

    /** Re-activates the value of a previously approved version as a new ACTIVE version; the current one is ROLLED_BACK. */
    @Transactional
    public String rollback(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("validation_failed", "reason is required");
        }
        JsonNode target = json.readTree(store.entry(id, false).orElseThrow(() -> ApiException.notFound("config entry", id)));
        String namespace = target.get("namespace").asString();
        String key = target.get("config_key").asString();
        store.lockKey(namespace, key);
        target = json.readTree(store.entry(id, true).orElseThrow());
        ConfigLifecycle.requireRollbackTarget(target.get("status").asString());
        JsonNode current = store.active(namespace, key).map(json::readTree)
                .orElseThrow(() -> ApiException.conflict("no_active_version",
                        "There is no active version of " + namespace + "." + key + " to roll back"));
        setStatus(current, Status.ROLLED_BACK);

        ObjectNode values = json.createObjectNode();
        values.put("id", Ids.newId().toString());
        values.put("namespace", namespace);
        values.put("config_key", key);
        values.put("version", store.maxVersion(namespace, key) + 1);
        values.set("value", target.get("value"));
        if (target.hasNonNull("value_schema")) {
            values.set("value_schema", target.get("value_schema"));
        }
        values.put("status", Status.ACTIVE.name());
        values.put("effective_from", OffsetDateTime.now().toString());
        String changeReason = "Rollback to v" + target.get("version").asInt() + ": " + reason;
        values.put("change_reason", changeReason.substring(0, Math.min(500, changeReason.length())));
        if (target.hasNonNull("approval_request_id")) {
            values.put("approval_request_id", target.get("approval_request_id").asString());
        }
        String row = crud.insert("config_entry", values);
        JsonNode rowNode = json.readTree(row);
        change(rowNode, current.get("version").asInt(), "ROLLED_BACK",
                JsonDiff.diff(json, current.get("value"), rowNode.get("value")), TenantContext.principal());
        outbox.publish("platform.config_entry.rolled_back", "config_entry", UUID.fromString(rowNode.get("id").asString()), rowNode);
        return row;
    }

    // ------------------------------------------------------------------ history

    public JsonNode entryNode(UUID id) {
        return json.readTree(store.entry(id, false).orElseThrow(() -> ApiException.notFound("config entry", id)));
    }

    // ------------------------------------------------------------------ export / import

    /** Exports ACTIVE configuration of the given namespaces (all when empty) as a checksummed bundle. */
    @Transactional(readOnly = true)
    public JsonNode export(List<String> namespaces, String environment) {
        ArrayNode entries = json.createArrayNode();
        for (String r : store.activeEntries(namespaces)) {
            JsonNode e = json.readTree(r);
            ObjectNode o = entries.addObject();
            o.put("namespace", e.get("namespace").asString());
            o.put("config_key", e.get("config_key").asString());
            o.put("version", e.get("version").asInt());
            o.set("value", e.get("value"));
            if (e.hasNonNull("value_schema")) {
                o.set("value_schema", e.get("value_schema"));
            }
        }
        ObjectNode bundle = json.createObjectNode();
        bundle.put("format", BUNDLE_FORMAT);
        bundle.put("exported_at", OffsetDateTime.now().toString());
        if (environment != null) {
            bundle.put("environment", environment);
        }
        ArrayNode ns = bundle.putArray("namespaces");
        namespaces.forEach(ns::add);
        bundle.put("entry_count", entries.size());
        bundle.set("entries", entries);
        bundle.put("checksum", JsonDiff.checksum(json, entries));
        return bundle;
    }

    /**
     * Imports a bundle produced by {@link #export}: verifies format and checksum, then creates a DRAFT version for
     * every entry whose value differs from the current ACTIVE value. Nothing is activated: imported drafts follow
     * the normal submit / approve / activate path.
     */
    @Transactional
    public JsonNode importBundle(JsonNode bundle) {
        if (bundle == null || !bundle.isObject() || !BUNDLE_FORMAT.equals(bundle.path("format").asString(""))) {
            throw ApiException.unprocessable("invalid_bundle", "bundle.format must be " + BUNDLE_FORMAT);
        }
        JsonNode entries = bundle.get("entries");
        if (entries == null || !entries.isArray()) {
            throw ApiException.unprocessable("invalid_bundle", "bundle.entries must be an array");
        }
        if (!JsonDiff.checksum(json, entries).equals(bundle.path("checksum").asString(""))) {
            throw ApiException.unprocessable("checksum_mismatch", "bundle checksum does not match its entries");
        }
        String sourceEnv = bundle.path("environment").asString(null);
        ArrayNode created = json.createArrayNode();
        ArrayNode skipped = json.createArrayNode();
        List<ApiException.FieldError> errors = new ArrayList<>();
        int i = 0;
        for (JsonNode e : entries) {
            String ns = e.path("namespace").asString(null);
            String key = e.path("config_key").asString(null);
            JsonNode value = e.get("value");
            if (ns == null || key == null || !NAME.matcher(ns).matches() || !NAME.matcher(key).matches()
                    || value == null || !value.isObject()) {
                errors.add(new ApiException.FieldError("entries[" + i + "]", "invalid_entry",
                        "needs namespace, config_key and an object value"));
                i++;
                continue;
            }
            List<ApiException.FieldError> schemaErrors = JsonSchemaValidator.validate(e.get("value_schema"), value);
            if (!schemaErrors.isEmpty()) {
                errors.add(new ApiException.FieldError("entries[" + i + "]", "schema_violation",
                        schemaErrors.getFirst().field() + " " + schemaErrors.getFirst().message()));
            }
            i++;
        }
        if (!errors.isEmpty()) {
            throw new ApiException(422, "invalid_bundle", "bundle contains invalid entries", errors);
        }
        for (JsonNode e : entries) {
            String ns = e.get("namespace").asString();
            String key = e.get("config_key").asString();
            JsonNode value = e.get("value");
            store.lockKey(ns, key);
            Optional<JsonNode> active = store.active(ns, key).map(json::readTree);
            Optional<JsonNode> open = store.latestOpen(ns, key).map(json::readTree);
            String skipReason = active.isPresent() && JsonDiff.diff(json, active.get().get("value"), value).isEmpty()
                    ? "identical_to_active"
                    : open.isPresent() && JsonDiff.diff(json, open.get().get("value"), value).isEmpty()
                    ? "identical_to_pending_version" : null;
            if (skipReason != null) {
                ObjectNode s = skipped.addObject();
                s.put("namespace", ns);
                s.put("config_key", key);
                s.put("reason", skipReason);
                continue;
            }
            ObjectNode values = json.createObjectNode();
            values.put("namespace", ns);
            values.put("config_key", key);
            values.set("value", value);
            if (e.hasNonNull("value_schema")) {
                values.set("value_schema", e.get("value_schema"));
            }
            if (sourceEnv != null) {
                values.put("promoted_from_environment", sourceEnv.substring(0, Math.min(32, sourceEnv.length())));
            }
            JsonNode row = json.readTree(insertDraft(ns, key, value, values,
                    "Imported from " + (sourceEnv == null ? "bundle" : sourceEnv)));
            ObjectNode c = created.addObject();
            c.put("id", row.get("id").asString());
            c.put("namespace", ns);
            c.put("config_key", key);
            c.put("version", row.get("version").asInt());
            c.put("status", row.get("status").asString());
        }
        ObjectNode result = json.createObjectNode();
        result.put("imported", created.size());
        result.put("skipped", skipped.size());
        result.set("created", created);
        result.set("skipped_entries", skipped);
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private void setStatus(JsonNode row, Status to) {
        ConfigLifecycle.require(row.get("status").asString(), to);
        ObjectNode upd = json.createObjectNode();
        upd.put("status", to.name());
        crud.updateRow("config_entry", java.util.Map.of("id", UUID.fromString(row.get("id").asString())), upd, null);
    }

    private void change(JsonNode entry, Integer fromVersion, String type, JsonNode diff, String approvedBy) {
        ObjectNode c = json.createObjectNode();
        c.put("config_entry_id", entry.get("id").asString());
        c.put("namespace", entry.get("namespace").asString());
        c.put("config_key", entry.get("config_key").asString());
        if (fromVersion != null) {
            c.put("from_version", fromVersion);
        }
        c.put("to_version", entry.get("version").asInt());
        c.put("change_type", type);
        c.set("diff", diffDocument(diff));
        if (approvedBy != null) {
            c.put("approved_by", approvedBy);
        }
        crud.insert("config_change", c);
    }

    /** The contract types diff as a JSON object, so the operation list is wrapped as {"operations": [...]}. */
    private ObjectNode diffDocument(JsonNode operations) {
        ObjectNode d = json.createObjectNode();
        d.put("format", "json-patch");
        d.set("operations", operations);
        return d;
    }

    private static void requireName(String field, String v) {
        if (v == null || !NAME.matcher(v).matches()) {
            throw new ApiException(422, "invalid_value", field + " must be lower-case letters, digits, '.', '_' or '-'",
                    List.of(new ApiException.FieldError(field, "invalid_format", "must match " + NAME.pattern())));
        }
    }

    private static String text(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asString() : null;
    }
}
