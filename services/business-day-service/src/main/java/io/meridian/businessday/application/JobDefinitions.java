package io.meridian.businessday.application;

import io.meridian.businessday.domain.CycleType;
import io.meridian.businessday.domain.DomainException;
import io.meridian.businessday.domain.EodPolicy;
import io.meridian.businessday.domain.JobGraph;
import io.meridian.businessday.infrastructure.JsonValues;
import io.meridian.businessday.infrastructure.Rows;
import io.meridian.businessday.infrastructure.ServiceCatalog;
import io.meridian.common.error.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** EOD job definitions as a dependency graph: validation on change, per-cycle plans, graph views. */
@Service
public class JobDefinitions {
    private final Rows rows;
    private final ServiceCatalog catalog;
    private final JsonMapper json;

    public JobDefinitions(Rows rows, ServiceCatalog catalog, JsonMapper json) {
        this.rows = rows;
        this.catalog = catalog;
        this.json = json;
    }

    /** A parsed definition row. */
    public record Definition(UUID id, String code, String name, String jobType, Set<CycleType> cycles,
                             String owningService, String httpMethod, String httpPath, JsonNode bodyTemplate,
                             List<String> dependsOn, int sequence,
                             int parallelism, String partitionKey, int timeoutMinutes, int maxRetries,
                             EodPolicy.FailurePolicy failurePolicy, boolean critical, boolean active,
                             UUID legalEntityId, JsonNode parameters) {
        JobGraph.Job job() {
            return new JobGraph.Job(code, dependsOn, sequence, critical);
        }
    }

    public Definition parse(JsonNode r) {
        Set<CycleType> cycles = new LinkedHashSet<>();
        JsonValues.strings(r.get("cycle_types")).forEach(c -> cycles.add(CycleType.parse(c)));
        return new Definition(
                r.hasNonNull("id") ? UUID.fromString(r.get("id").asString()) : null,
                r.path("code").asString(), r.path("name").asString(), r.path("job_type").asString(), cycles,
                r.path("owning_service").asString(), r.path("http_method").asString("POST"),
                r.path("http_path").asString(), r.hasNonNull("body_template") ? r.get("body_template") : null,
                JsonValues.strings(r.get("depends_on")), r.path("sequence").asInt(100),
                r.path("parallelism").asInt(1), JsonValues.text(r, "partition_key"),
                r.path("timeout_minutes").asInt(60), r.path("max_retries").asInt(3),
                EodPolicy.FailurePolicy.of(JsonValues.text(r, "failure_policy")),
                r.path("is_critical").asBoolean(true), r.path("is_active").asBoolean(true),
                r.hasNonNull("legal_entity_id") ? UUID.fromString(r.get("legal_entity_id").asString()) : null,
                r.get("parameters"));
    }

    public List<Definition> all() {
        return rows.query("select row_to_json(d)::text from eod_job_definition d order by sequence, code")
                .stream().map(this::parse).toList();
    }

    public Map<UUID, Definition> byId() {
        Map<UUID, Definition> m = new LinkedHashMap<>();
        all().forEach(d -> m.put(d.id(), d));
        return m;
    }

    /** Field rules for a definition about to be stored (merged values for updates). */
    public void validateFields(JsonNode values) {
        List<String> cycles = JsonValues.strings(values.get("cycle_types"));
        if (cycles.isEmpty()) {
            throw ApiException.unprocessable("cycle_types_required", "cycle_types must list at least one cycle, e.g. [\"EOD\"]");
        }
        cycles.forEach(CycleType::parse);
        String service = JsonValues.text(values, "owning_service");
        if (service == null || !catalog.exists(service)) {
            throw ApiException.unprocessable("unknown_service", "owning_service " + service + " is not a Meridian service");
        }
        JobTemplates.validate(JsonValues.text(values, "http_path"), values.get("body_template"));
        range(values, "parallelism", 1, 64);
        range(values, "timeout_minutes", 1, 1440);
        range(values, "max_retries", 0, 10);
        String code = JsonValues.text(values, "code");
        if (code != null && JsonValues.strings(values.get("depends_on")).contains(code)) {
            throw ApiException.unprocessable("self_dependency", "Job " + code + " cannot depend on itself");
        }
    }

    /**
     * Validates the whole graph as it would be after replacing definition {@code replacingId} (null for a new
     * one) with {@code candidate}; rejects cycles and unknown dependencies with 422.
     */
    public void validateGraphWith(UUID replacingId, JsonNode candidate) {
        List<JobGraph.Job> jobs = new ArrayList<>();
        for (Definition d : all()) {
            if (!d.id().equals(replacingId)) {
                jobs.add(d.job());
            }
        }
        if (candidate != null) {
            jobs.add(parse(candidate).job());
        }
        JobGraph.of(jobs).requireValid();
    }

    /** Codes of definitions that depend on {@code code}. */
    public List<String> dependentsOf(String code) {
        return all().stream().filter(d -> d.dependsOn().contains(code)).map(Definition::code).toList();
    }

    /** Active jobs of a cycle for a legal entity (entity-specific and all-entity jobs). */
    public List<Definition> planFor(CycleType cycle, UUID legalEntityId) {
        Set<CycleType> included = cycle.includedCycles();
        return all().stream()
                .filter(Definition::active)
                .filter(d -> d.legalEntityId() == null || Objects.equals(d.legalEntityId(), legalEntityId))
                .filter(d -> d.cycles().stream().anyMatch(included::contains))
                .toList();
    }

    public JobGraph graphOf(List<Definition> defs) {
        Set<String> codes = defs.stream().map(Definition::code).collect(Collectors.toSet());
        return JobGraph.of(defs.stream().map(Definition::job).toList()).restrictTo(codes);
    }

    /** Graph view for the console: nodes with level, edges, execution levels and validation findings. */
    public ObjectNode graphView(CycleType cycle, UUID legalEntityId) {
        List<Definition> defs = cycle == null ? all().stream().filter(Definition::active).toList()
                : planFor(cycle, legalEntityId);
        ObjectNode out = json.createObjectNode();
        out.put("cycle_type", cycle == null ? null : cycle.name());
        ArrayNode issues = issuesNode(JobGraph.of(all().stream().map(Definition::job).toList()).validate());
        out.put("valid", issues.isEmpty());
        out.set("issues", issues);
        JobGraph g = graphOf(defs);
        Map<String, Integer> levelOf = new LinkedHashMap<>();
        ArrayNode levels = out.putArray("levels");
        if (issues.isEmpty()) {
            List<List<String>> lv = g.levels();
            for (int i = 0; i < lv.size(); i++) {
                ArrayNode a = levels.addArray();
                for (String c : lv.get(i)) {
                    a.add(c);
                    levelOf.put(c, i);
                }
            }
        }
        ArrayNode nodes = out.putArray("nodes");
        ArrayNode edges = out.putArray("edges");
        for (Definition d : defs) {
            ObjectNode n = nodes.addObject();
            n.put("id", d.id().toString());
            n.put("code", d.code());
            n.put("name", d.name());
            n.put("job_type", d.jobType());
            n.put("owning_service", d.owningService());
            n.put("http_method", d.httpMethod());
            n.put("http_path", d.httpPath());
            n.put("sequence", d.sequence());
            n.put("parallelism", d.parallelism());
            n.put("is_critical", d.critical());
            n.put("failure_policy", d.failurePolicy().name());
            ArrayNode cyc = n.putArray("cycle_types");
            d.cycles().forEach(c -> cyc.add(c.name()));
            ArrayNode deps = n.putArray("depends_on");
            g.jobs().get(d.code()).dependsOn().forEach(dep -> {
                deps.add(dep);
                edges.addObject().put("from", dep).put("to", d.code());
            });
            if (levelOf.containsKey(d.code())) {
                n.put("level", levelOf.get(d.code()));
            }
        }
        out.put("job_count", defs.size());
        return out;
    }

    /** Full validation report: graph-wide findings plus a per-cycle plan check. */
    public ObjectNode validationReport() {
        List<Definition> defs = all();
        JobGraph full = JobGraph.of(defs.stream().map(Definition::job).toList());
        List<JobGraph.Issue> issues = new ArrayList<>(full.validate());
        for (Definition d : defs) {
            if (d.active()) {
                for (String dep : d.dependsOn()) {
                    defs.stream().filter(x -> x.code().equals(dep) && !x.active()).findFirst().ifPresent(x ->
                            issues.add(new JobGraph.Issue("inactive_dependency", d.code(),
                                    "Job " + d.code() + " depends on inactive job " + dep + " (dependency is ignored)")));
                }
            }
        }
        ObjectNode out = json.createObjectNode();
        boolean valid = issues.stream().noneMatch(i -> !i.type().equals("inactive_dependency"));
        out.put("valid", valid);
        out.put("job_count", defs.size());
        out.set("issues", issuesNode(issues));
        ObjectNode cycles = out.putObject("cycles");
        if (valid) {
            for (CycleType c : CycleType.values()) {
                List<Definition> plan = defs.stream().filter(Definition::active)
                        .filter(d -> d.cycles().stream().anyMatch(c.includedCycles()::contains)).toList();
                ObjectNode cn = cycles.putObject(c.name());
                cn.put("job_count", plan.size());
                ArrayNode lv = cn.putArray("levels");
                graphOf(plan).levels().forEach(l -> {
                    ArrayNode a = lv.addArray();
                    l.forEach(a::add);
                });
            }
        }
        return out;
    }

    private ArrayNode issuesNode(List<JobGraph.Issue> issues) {
        ArrayNode a = json.createArrayNode();
        issues.forEach(i -> a.addObject().put("type", i.type()).put("job_code", i.jobCode()).put("message", i.message()));
        return a;
    }

    private static void range(JsonNode values, String field, int min, int max) {
        if (values.hasNonNull(field)) {
            int v = values.get(field).asInt();
            if (v < min || v > max) {
                throw ApiException.unprocessable("invalid_" + field, field + " must be between " + min + " and " + max);
            }
        }
    }

    static DomainException notFound(UUID id) {
        return new DomainException(404, "not_found", "eod job definition " + id + " was not found");
    }
}
