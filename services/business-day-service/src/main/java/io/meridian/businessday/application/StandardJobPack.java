package io.meridian.businessday.application;

import io.meridian.businessday.domain.JobGraph;
import io.meridian.businessday.infrastructure.JsonValues;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The standard EOD job pack shipped with the service ({@code eod/standard-pack.json}): every domain's per-tenant
 * EOD, scan and expiry endpoint with its owning service, method, path template, dependencies in banking order,
 * criticality and cycle applicability. Versioned; installed into a tenant's job definitions by
 * {@link JobPackInstaller}. The pack owns the contract fields of its jobs ({@link #MANAGED}); tenants tune the
 * others (activation, parallelism, timeouts, retries, parameters).
 */
@Component
public class StandardJobPack {
    public static final String RESOURCE = "eod/standard-pack.json";
    /** Definition columns owned by the pack: an upgrade resets them to the pack's values. */
    public static final List<String> MANAGED = List.of("name", "job_type", "cycle_types", "owning_service", "http_method",
            "http_path", "body_template", "depends_on", "sequence", "is_critical", "failure_policy");

    /** One job of the pack, as a definition column set plus pack-only metadata. */
    public record Job(String code, String stage, ObjectNode columns, List<String> requiredParameters) {
        public List<String> dependsOn() {
            return JsonValues.strings(columns.get("depends_on"));
        }

        public String service() {
            return columns.get("owning_service").asString();
        }

        public String method() {
            return columns.path("http_method").asString("POST");
        }

        public String path() {
            return columns.get("http_path").asString();
        }

        public boolean active() {
            return columns.path("is_active").asBoolean(true);
        }
    }

    private final JsonNode raw;
    private final String code;
    private final String version;
    private final List<Job> jobs;

    @org.springframework.beans.factory.annotation.Autowired
    public StandardJobPack(JsonMapper json) {
        this(read(json));
    }

    StandardJobPack(JsonNode raw) {
        this.raw = raw;
        this.code = raw.get("pack_code").asString();
        this.version = raw.get("version").asString();
        List<Job> list = new ArrayList<>();
        for (JsonNode j : raw.get("jobs")) {
            ObjectNode cols = ((ObjectNode) j).deepCopy();
            String stage = cols.path("stage").asString(null);
            List<String> required = JsonValues.strings(cols.get("required_parameters"));
            cols.remove("stage");
            cols.remove("required_parameters");
            list.add(new Job(cols.get("code").asString(), stage, cols, required));
        }
        this.jobs = List.copyOf(list);
    }

    /** Loads a pack document (used by tests and tooling). */
    public static StandardJobPack load(JsonMapper json, InputStream in) {
        return new StandardJobPack(json.readTree(in));
    }

    private static JsonNode read(JsonMapper json) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
    }

    public String code() {
        return code;
    }

    public String version() {
        return version;
    }

    public List<Job> jobs() {
        return jobs;
    }

    /** The pack document as shipped. */
    public JsonNode document() {
        return raw;
    }

    /** The pack's dependency graph (every job, active or not). */
    public JobGraph graph() {
        return JobGraph.of(jobs.stream().map(j -> new JobGraph.Job(j.code(), j.dependsOn(),
                j.columns().path("sequence").asInt(100), j.columns().path("is_critical").asBoolean(true))).toList());
    }
}
