package io.meridian.config.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.config.api.generated.ConfigEntriesApi;
import io.meridian.config.api.generated.model.ApprovalRequest;
import io.meridian.config.api.generated.model.ConfigChangePage;
import io.meridian.config.api.generated.model.ConfigEntriesExportRequest;
import io.meridian.config.api.generated.model.ConfigEntriesImportRequest;
import io.meridian.config.api.generated.model.ConfigEntriesRollbackRequest;
import io.meridian.config.api.generated.model.ConfigEntriesSubmitRequest;
import io.meridian.config.api.generated.model.ConfigEntry;
import io.meridian.config.api.generated.model.ConfigEntryCreate;
import io.meridian.config.application.ConfigEntryService;
import io.meridian.config.application.ConfigResolver;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Config entries: versioned configuration with maker-checker activation, rollback, history, export/import and
 * effective-value resolution. Contract: generated/openapi/services/config-service.openapi.yaml
 */
@RestController
public class ConfigEntriesController implements ConfigEntriesApi {
    private final ConfigEntryService entries;
    private final ConfigResolver resolver;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public ConfigEntriesController(ConfigEntryService entries, ConfigResolver resolver, CrudEngine crud,
                                   Idempotency idempotency, JsonMapper json) {
        this.entries = entries;
        this.resolver = resolver;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<ConfigEntry> configEntriesCreate(String idempotencyKey, ConfigEntryCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, ConfigEntry.class,
                () -> Responses.row(json, HttpStatus.CREATED, entries.create(body), ConfigEntry.class));
    }

    @Override
    public ResponseEntity<ApprovalRequest> configEntriesSubmit(UUID id, String idempotencyKey,
                                                               ConfigEntriesSubmitRequest body) {
        TenantContext.require();
        String reason = body == null ? null : body.getChangeReason();
        return idempotency.execute(idempotencyKey, Responses.request("submit", id, body), ApprovalRequest.class,
                () -> ResponseEntity.ok(json.treeToValue(entries.submit(id, reason, idempotencyKey), ApprovalRequest.class)));
    }

    @Override
    public ResponseEntity<ConfigEntry> configEntriesActivate(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, Responses.request("activate", id, null), ConfigEntry.class,
                () -> Responses.row(json, HttpStatus.OK, entries.activate(id), ConfigEntry.class));
    }

    @Override
    public ResponseEntity<ConfigEntry> configEntriesRollback(UUID id, String idempotencyKey,
                                                             ConfigEntriesRollbackRequest body) {
        TenantContext.require();
        String reason = body == null ? null : body.getReason();
        return idempotency.execute(idempotencyKey, Responses.request("rollback", id, body), ConfigEntry.class,
                () -> Responses.row(json, HttpStatus.CREATED, entries.rollback(id, reason), ConfigEntry.class));
    }

    @Override
    public ResponseEntity<ConfigChangePage> configEntriesHistory(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        JsonNode entry = entries.entryNode(id);
        return crud.list("config_change", ConfigChangePage.class, Crud.params()
                .with("namespace", entry.get("namespace").asString())
                .with("config_key", entry.get("config_key").asString())
                .with("limit", limit).with("cursor", cursor));
    }

    /** Exports the ACTIVE configuration of the listed namespaces (all when empty or missing). */
    @Override
    public ResponseEntity<Map<String, Object>> configEntriesExport(String idempotencyKey, ConfigEntriesExportRequest body) {
        TenantContext.require();
        List<String> namespaces = body == null || body.getNamespaces() == null ? List.of()
                : body.getNamespaces().stream().filter(Objects::nonNull).map(String::valueOf).toList();
        String environment = body == null ? null : body.getEnvironment();
        return Responses.object(json, idempotency.execute(idempotencyKey, Responses.request("export", null, body),
                JsonNode.class, () -> ResponseEntity.ok(entries.export(namespaces, environment))));
    }

    @Override
    public ResponseEntity<Map<String, Object>> configEntriesImport(String idempotencyKey, ConfigEntriesImportRequest body) {
        TenantContext.require();
        JsonNode bundle = body == null || body.getBundle() == null ? null : json.valueToTree(body.getBundle());
        return Responses.object(json, idempotency.execute(idempotencyKey, Responses.request("import", null, body),
                JsonNode.class, () -> ResponseEntity.ok(entries.importBundle(bundle))));
    }

    @Override
    public ResponseEntity<Map<String, Object>> configEntriesResolve(String namespace, UUID legalEntityId, String country) {
        TenantContext.require();
        return Responses.object(json, ResponseEntity.ok(resolver.resolve(namespace, legalEntityId, country)));
    }
}
