package io.meridian.businessday.api;

import io.meridian.businessday.api.generated.EodJobDefinitionsApi;
import io.meridian.businessday.api.generated.model.EodJobDefinition;
import io.meridian.businessday.api.generated.model.EodJobDefinitionCreate;
import io.meridian.businessday.api.generated.model.EodJobDefinitionUpdate;
import io.meridian.businessday.api.generated.model.EodJobDefinitionsInstallStandardPackRequest;
import io.meridian.businessday.application.JobDefinitions;
import io.meridian.businessday.application.JobPackInstaller;
import io.meridian.businessday.application.StandardJobPack;
import io.meridian.businessday.domain.CycleType;
import io.meridian.businessday.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * EOD job definitions. Every change is validated against the whole job graph (no cycles, no unknown
 * dependencies); jobs other jobs depend on, or that have executions, cannot be deleted (deactivate instead).
 */
@RestController
public class EodJobDefinitionsController implements EodJobDefinitionsApi {
    private static final String TABLE = "eod_job_definition";

    private final JobDefinitions definitions;
    private final CrudEngine crud;
    private final Rows rows;
    private final TransactionTemplate tx;
    private final io.meridian.web.idempotency.Idempotency idempotency;
    private final JsonMapper json;
    private final StandardJobPack pack;
    private final JobPackInstaller installer;

    public EodJobDefinitionsController(JobDefinitions definitions, CrudEngine crud, Rows rows, TransactionTemplate tx,
                                       io.meridian.web.idempotency.Idempotency idempotency, JsonMapper json,
                                       StandardJobPack pack, JobPackInstaller installer) {
        this.pack = pack;
        this.installer = installer;
        this.definitions = definitions;
        this.crud = crud;
        this.rows = rows;
        this.tx = tx;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<EodJobDefinition> eodJobDefinitionsCreate(String idempotencyKey, EodJobDefinitionCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, EodJobDefinition.class, () -> tx.execute(s -> {
            ObjectNode values = crud.toColumns(TABLE, body);
            definitions.validateFields(values);
            lockGraph();
            definitions.validateGraphWith(null, values);
            return ResponseEntity.status(HttpStatus.CREATED).body(crud.read(crud.insert(TABLE, values), EodJobDefinition.class));
        }));
    }

    @Override
    public ResponseEntity<EodJobDefinition> eodJobDefinitionsUpdate(UUID id, String ifMatch, EodJobDefinitionUpdate body) {
        TenantContext.require();
        int version = Http.ifMatch(ifMatch);
        return tx.execute(s -> {
            lockGraph();
            JsonNode current = rows.require(TABLE, id, true);
            ObjectNode changes = crud.toColumns(TABLE, body);
            ObjectNode merged = ((ObjectNode) current).deepCopy();
            merged.setAll(changes);
            definitions.validateFields(merged);
            String oldCode = current.get("code").asString();
            String newCode = merged.get("code").asString();
            if (!oldCode.equals(newCode) && !definitions.dependentsOf(oldCode).isEmpty()) {
                throw ApiException.conflict("job_has_dependents", "Job " + oldCode + " is a dependency of "
                        + definitions.dependentsOf(oldCode) + "; it cannot be renamed");
            }
            definitions.validateGraphWith(id, merged);
            String row = crud.updateRow(TABLE, Map.of("id", id), changes, version)
                    .orElseThrow(() -> ApiException.conflict("version_conflict",
                            "If-Match " + version + " does not match the current version; reload and retry"));
            return ResponseEntity.ok().eTag("\"" + json.readTree(row).get("row_version").asString() + "\"")
                    .body(crud.read(row, EodJobDefinition.class));
        });
    }

    @Override
    public ResponseEntity<Void> eodJobDefinitionsDelete(UUID id) {
        TenantContext.require();
        return tx.execute(s -> {
            lockGraph();
            JsonNode current = rows.require(TABLE, id, true);
            List<String> dependents = definitions.dependentsOf(current.get("code").asString());
            if (!dependents.isEmpty()) {
                throw ApiException.conflict("job_has_dependents", "Jobs " + dependents + " depend on "
                        + current.get("code").asString() + "; remove the dependency first");
            }
            long executions = rows.jdbc().sql("select count(*) from eod_job_run where eod_job_definition_id = ?")
                    .param(id).query(Long.class).single();
            if (executions > 0) {
                throw ApiException.conflict("job_definition_in_use",
                        "The job has executions in EOD runs; deactivate it (is_active=false) instead of deleting");
            }
            rows.update("delete from eod_job_definition where id = ?", id);
            return ResponseEntity.noContent().build();
        });
    }

    /** Dependency graph of the active jobs of a cycle (all active jobs without {@code cycle_type}). */
    @Override
    public ResponseEntity<Map<String, Object>> eodJobDefinitionsGraph(String cycleType, UUID legalEntityId) {
        TenantContext.require();
        return ResponseEntity.ok(Http.object(json, definitions.graphView(
                cycleType == null || cycleType.isBlank() ? null : CycleType.parse(cycleType), legalEntityId)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> eodJobDefinitionsValidate(String idempotencyKey) {
        TenantContext.require();
        return Http.object(json, idempotency.execute(idempotencyKey, json.createObjectNode().put("op", "validate"),
                JsonNode.class, () -> ResponseEntity.ok(definitions.validationReport())));
    }

    /** The standard EOD job pack shipped with this service version. */
    @Override
    public ResponseEntity<Map<String, Object>> eodJobDefinitionsStandardPack() {
        TenantContext.require();
        return ResponseEntity.ok(Http.object(json, pack.document()));
    }

    /** Installs or upgrades the standard pack into the tenant's definitions (idempotent, optional dry run). */
    @Override
    public ResponseEntity<Map<String, Object>> eodJobDefinitionsInstallStandardPack(
            String idempotencyKey, EodJobDefinitionsInstallStandardPackRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        boolean dryRun = req.path("dry_run").asBoolean(false);
        return Http.object(json, idempotency.execute(idempotencyKey, req, JsonNode.class,
                () -> ResponseEntity.ok(installer.install(Http.text(req, "version"), dryRun))));
    }

    /** Serialises graph changes within the tenant so two concurrent edits cannot together create a cycle. */
    private void lockGraph() {
        rows.jdbc().sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) l")
                .param("eodgraph:" + TenantContext.require()).query(Integer.class).single();
    }
}
