package io.meridian.config.api;

import io.meridian.common.error.ApiException;
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
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
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
    private final HttpServletRequest request;

    public ConfigEntriesController(ConfigEntryService entries, ConfigResolver resolver, CrudEngine crud,
                                   Idempotency idempotency, JsonMapper json, HttpServletRequest request) {
        this.entries = entries;
        this.resolver = resolver;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
        this.request = request;
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
        String reason = body == null ? null : json.valueToTree(body).path("change_reason").asString(null);
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
        String reason = body == null ? null : json.valueToTree(body).path("reason").asString(null);
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

    /**
     * Contract defect: {@code namespaces} is declared as a JSON object rather than an array. Accepted shapes:
     * {@code {"namespaces": {"names": ["payments", "limits"]}}} or {@code {"namespaces": {"payments": true}}}
     * (keys are namespace names). Empty or missing exports every namespace.
     */
    @Override
    public ResponseEntity<Void> configEntriesExport(String idempotencyKey, ConfigEntriesExportRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        List<String> namespaces = new ArrayList<>();
        JsonNode ns = req.get("namespaces");
        if (ns != null && ns.isObject()) {
            JsonNode names = ns.get("names");
            if (names != null && names.isArray()) {
                names.forEach(n -> namespaces.add(n.asString()));
            } else {
                namespaces.addAll(ns.propertyNames());
            }
        } else if (ns != null && ns.isArray()) {
            ns.forEach(n -> namespaces.add(n.asString()));
        }
        String environment = request.getParameter("environment");
        return Responses.body(idempotency.execute(idempotencyKey, Responses.request("export", null, body), JsonNode.class,
                () -> ResponseEntity.ok(entries.export(namespaces, environment))));
    }

    @Override
    public ResponseEntity<Void> configEntriesImport(String idempotencyKey, ConfigEntriesImportRequest body) {
        TenantContext.require();
        JsonNode bundle = json.valueToTree(body).get("bundle");
        return Responses.body(idempotency.execute(idempotencyKey, Responses.request("import", null, body), JsonNode.class,
                () -> ResponseEntity.ok(entries.importBundle(bundle))));
    }

    /** Contract defect: the generated signature has no query parameters; they are read from the request. */
    @Override
    public ResponseEntity<Void> configEntriesResolve() {
        TenantContext.require();
        UUID legalEntityId = null;
        String le = request.getParameter("legal_entity_id");
        if (le != null && !le.isBlank()) {
            try {
                legalEntityId = UUID.fromString(le.trim());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("invalid_value", "legal_entity_id must be a UUID");
            }
        }
        String country = request.getParameter("country");
        if (country != null && !country.matches("^[A-Z]{2}$")) {
            throw ApiException.badRequest("invalid_value", "country must be an ISO 3166 alpha-2 code");
        }
        return Responses.body(ResponseEntity.ok(resolver.resolve(request.getParameter("namespace"), legalEntityId, country)));
    }
}
