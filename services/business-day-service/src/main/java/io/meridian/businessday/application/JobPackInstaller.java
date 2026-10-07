package io.meridian.businessday.application;

import io.meridian.businessday.domain.JobGraph;
import io.meridian.businessday.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Installs or upgrades the {@link StandardJobPack} into the current tenant's EOD job definitions, idempotently:
 * <ul>
 *   <li>a pack job without a definition of the same code is created (with the pack's activation and defaults);</li>
 *   <li>a definition installed from the pack whose managed fields differ is upgraded (tenant-tuned fields such as
 *       is_active, parallelism, timeouts, retries and parameters are kept);</li>
 *   <li>a tenant-defined job with the same code is never touched (reported as skipped);</li>
 *   <li>a job installed from the pack but no longer in it is deactivated (retired);</li>
 *   <li>an inactive definition that only waited for path parameters the new pack version no longer needs is
 *       activated (reported as {@code activated}).</li>
 * </ul>
 * The resulting graph is validated as a whole; any problem rolls the installation back. {@code dry_run} reports the
 * changes without keeping them.
 */
@Service
public class JobPackInstaller {
    private static final String TABLE = "eod_job_definition";

    private final StandardJobPack pack;
    private final JobDefinitions definitions;
    private final Rows rows;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public JobPackInstaller(StandardJobPack pack, JobDefinitions definitions, Rows rows, CrudEngine crud,
                            OutboxWriter outbox, TransactionTemplate tx, JsonMapper json) {
        this.pack = pack;
        this.definitions = definitions;
        this.rows = rows;
        this.crud = crud;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
    }

    public ObjectNode install(String version, boolean dryRun) {
        TenantContext.require();
        if (version != null && !version.isBlank() && !version.equals(pack.version())) {
            throw ApiException.unprocessable("unknown_pack_version", "This service ships " + pack.code() + " version "
                    + pack.version() + "; version " + version + " is not available");
        }
        return tx.execute(status -> {
            ObjectNode result = apply();
            result.put("dry_run", dryRun);
            if (dryRun) {
                status.setRollbackOnly();
            }
            return result;
        });
    }

    private ObjectNode apply() {
        lockGraph();
        Map<String, JsonNode> existing = new LinkedHashMap<>();
        rows.query("select row_to_json(d)::text from eod_job_definition d order by code")
                .forEach(r -> existing.put(r.get("code").asString(), r));
        ObjectNode out = json.createObjectNode();
        out.put("pack_code", pack.code());
        out.put("version", pack.version());
        ArrayNode created = out.putArray("created");
        ArrayNode updated = out.putArray("updated");
        ArrayNode unchanged = out.putArray("unchanged");
        ArrayNode retired = out.putArray("retired");
        ArrayNode skipped = out.putArray("skipped");
        ArrayNode inactive = out.putArray("needs_parameters");
        ArrayNode activated = out.putArray("activated");

        for (StandardJobPack.Job job : pack.jobs()) {
            JsonNode current = existing.get(job.code());
            if (current == null) {
                ObjectNode values = job.columns().deepCopy();
                if (!job.requiredParameters().isEmpty()) {
                    ObjectNode params = values.putObject("parameters");
                    job.requiredParameters().forEach(params::putNull);
                    inactive.add(job.code());
                }
                values.put("pack_code", pack.code());
                values.put("pack_version", pack.version());
                definitions.validateFields(values);
                String row = crud.insert(TABLE, values);
                publish(row);
                created.add(job.code());
            } else if (!pack.code().equals(current.path("pack_code").asString(null))) {
                skipped.addObject().put("code", job.code()).put("reason", "tenant_defined")
                        .put("message", "A tenant-defined job uses this code; it is left unchanged");
            } else if (differs(job, current) || !pack.version().equals(current.path("pack_version").asString(null))) {
                ObjectNode merged = ((ObjectNode) current).deepCopy();
                ObjectNode changes = json.createObjectNode();
                for (String f : StandardJobPack.MANAGED) {
                    JsonNode v = job.columns().get(f);
                    changes.set(f, v == null ? json.nullNode() : v.deepCopy());
                }
                changes.put("pack_version", pack.version());
                if (waitedOnlyForParameters(job, current)) {
                    changes.put("is_active", true);
                    activated.add(job.code());
                }
                merged.setAll(changes);
                definitions.validateFields(merged);
                String row = crud.updateRow(TABLE, Map.of("id", UUID.fromString(current.get("id").asString())), changes,
                        current.get("row_version").asInt()).orElseThrow(() -> ApiException.conflict("version_conflict",
                        "Job " + job.code() + " changed concurrently; retry the installation"));
                publish(row);
                (differs(job, current) ? updated : unchanged).add(job.code());
            } else {
                unchanged.add(job.code());
            }
        }
        List<String> packCodes = pack.jobs().stream().map(StandardJobPack.Job::code).toList();
        existing.values().stream()
                .filter(r -> pack.code().equals(r.path("pack_code").asString(null)))
                .filter(r -> !packCodes.contains(r.get("code").asString()))
                .filter(r -> r.path("is_active").asBoolean(false))
                .forEach(r -> {
                    rows.update("update eod_job_definition set is_active = false, updated_by = ?, "
                            + "row_version = row_version + 1, updated_at = now() where id = ?",
                            TenantContext.principal(), UUID.fromString(r.get("id").asString()));
                    retired.add(r.get("code").asString());
                });
        JobGraph.of(definitions.all().stream().map(d -> new JobGraph.Job(d.code(), d.dependsOn(), d.sequence(),
                d.critical())).toList()).requireValid();
        out.put("job_count", pack.jobs().size());
        return out;
    }

    /**
     * True when the stored definition is inactive only because its (older) path needed tenant parameters that were
     * never set, and the pack's job ships active without needing any (e.g. LEDGER_EOD moved from one book to the
     * tenant-wide endpoint): the upgrade activates it. A job the tenant deactivated with its parameters set stays off.
     */
    public static boolean waitedOnlyForParameters(StandardJobPack.Job job, JsonNode current) {
        if (current.path("is_active").asBoolean(false) || !job.active() || !job.requiredParameters().isEmpty()) {
            return false;
        }
        List<String> needed = JobTemplates.pathParameters(current.path("http_path").asString(null));
        JsonNode params = current.get("parameters");
        return !needed.isEmpty() && needed.stream().anyMatch(p -> params == null || !params.hasNonNull(p));
    }

    /** True when any pack-managed field of the stored definition differs from the pack. */
    boolean differs(StandardJobPack.Job job, JsonNode current) {
        for (String f : StandardJobPack.MANAGED) {
            JsonNode want = job.columns().get(f);
            JsonNode have = current.get(f);
            boolean wantNull = want == null || want.isNull();
            boolean haveNull = have == null || have.isNull();
            if (wantNull != haveNull) {
                return true;
            }
            if (!wantNull && !Objects.equals(normalise(want), normalise(have))) {
                return true;
            }
        }
        return false;
    }

    private static Object normalise(JsonNode n) {
        return n.isValueNode() ? n.asString() : n;
    }

    private void publish(String row) {
        JsonNode n = json.readTree(row);
        outbox.publish("operations.eod_job_definition.installed", TABLE, UUID.fromString(n.get("id").asString()), n);
    }

    /** Same advisory lock as definition edits, so an installation and an edit cannot interleave. */
    private void lockGraph() {
        rows.jdbc().sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) l")
                .param("eodgraph:" + TenantContext.require()).query(Integer.class).single();
    }
}
