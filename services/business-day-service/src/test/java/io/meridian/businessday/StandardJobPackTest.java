package io.meridian.businessday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.meridian.businessday.application.JobPackInstaller;
import io.meridian.businessday.application.JobTemplates;
import io.meridian.businessday.application.StandardJobPack;
import io.meridian.businessday.domain.CycleType;
import io.meridian.businessday.domain.EodPolicy;
import io.meridian.businessday.domain.JobGraph;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The standard EOD job pack against the generated contracts: every job targets an existing route of its owning
 * service (generated/routes.json) with a valid method, supplies the operation's required query parameters and body
 * fields, uses only known placeholders, enum values and cycles, and the dependency graph is acyclic and in banking
 * order.
 */
class StandardJobPackTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static StandardJobPack pack;
    static JsonNode routes;
    static Path root;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = StandardJobPackTest.class.getClassLoader().getResourceAsStream(StandardJobPack.RESOURCE)) {
            assertThat(in).as("pack resource").isNotNull();
            pack = StandardJobPack.load(JSON, in);
        }
        try (InputStream in = StandardJobPackTest.class.getClassLoader().getResourceAsStream("meridian/routes.json")) {
            assertThat(in).as("generated routes.json on the classpath").isNotNull();
            routes = JSON.readTree(in);
        }
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("generated/routes.json"))) {
            p = p.getParent();
        }
        assertThat(p).as("repository root with generated/").isNotNull();
        root = p;
    }

    @Test
    void packIsVersionedAndCodesAreUnique() {
        assertThat(pack.code()).isEqualTo("MERIDIAN_STANDARD_EOD");
        assertThat(pack.version()).matches("\\d{4}\\.\\d{1,2}\\.\\d+");
        List<String> codes = pack.jobs().stream().map(StandardJobPack.Job::code).toList();
        assertThat(codes).doesNotHaveDuplicates().hasSizeGreaterThanOrEqualTo(40);
        codes.forEach(c -> assertThat(c).matches("[A-Z][A-Z0-9_]{2,63}"));
    }

    @Test
    void everyJobResolvesToAnExistingRouteWithAValidMethod() {
        for (StandardJobPack.Job job : pack.jobs()) {
            assertThat(job.method()).as(job.code()).isIn("POST", "PUT", "PATCH", "GET");
            String path = job.path().contains("?") ? job.path().substring(0, job.path().indexOf('?')) : job.path();
            List<JsonNode> matches = new ArrayList<>();
            routes.forEach(r -> {
                if (r.get("method").asString().equals(job.method()) && pathMatches(r.get("path").asString(), path)) {
                    matches.add(r);
                }
            });
            assertThat(matches).as("route %s %s of %s", job.method(), path, job.code()).hasSize(1);
            JsonNode route = matches.getFirst();
            assertThat(route.get("service").asString()).as(job.code()).isEqualTo(job.service());
            assertThat(route.path("public").asBoolean(false)).as(job.code() + " must not be a public callback").isFalse();
        }
    }

    @Test
    void templatesUseKnownPlaceholdersAndSatisfyTheOperationContract() throws IOException {
        Map<String, Map<String, Object>> specs = new HashMap<>();
        for (StandardJobPack.Job job : pack.jobs()) {
            JsonNode body = job.columns().get("body_template");
            assertThatCode(() -> JobTemplates.validate(job.path(), body)).as(job.code()).doesNotThrowAnyException();
            Map<String, Object> spec = specs.computeIfAbsent(job.service(), StandardJobPackTest::serviceSpec);
            String path = job.path().contains("?") ? job.path().substring(0, job.path().indexOf('?')) : job.path();
            Map<String, Object> op = operation(spec, job.method().toLowerCase(), path);
            assertThat(op).as("operation of " + job.code()).isNotNull();

            Set<String> query = new HashSet<>();
            if (job.path().contains("?")) {
                for (String kv : job.path().substring(job.path().indexOf('?') + 1).split("&")) {
                    query.add(kv.split("=")[0]);
                }
            }
            for (Map<String, Object> param : parameters(spec, op)) {
                if ("query".equals(param.get("in")) && Boolean.TRUE.equals(param.get("required"))) {
                    assertThat(query).as(job.code() + " required query parameter").contains((String) param.get("name"));
                }
            }
            List<String> requiredBody = requiredBodyFields(spec, op);
            for (String f : requiredBody) {
                assertThat(body).as(job.code() + " needs a body_template with " + f).isNotNull();
                assertThat(body.has(f)).as(job.code() + " body field " + f).isTrue();
            }
        }
    }

    @Test
    void enumsCyclesServicesAndParametersAreValid() throws IOException {
        Map<String, Object> own = serviceSpec("business-day-service");
        Map<String, Object> schema = schema(own, "EodJobDefinition");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        List<String> jobTypes = ((List<?>) ((Map<?, ?>) props.get("job_type")).get("enum")).stream()
                .map(String::valueOf).toList();
        JsonNode services;
        try (InputStream in = StandardJobPackTest.class.getClassLoader().getResourceAsStream("meridian/services.json")) {
            services = JSON.readTree(in);
        }
        Set<String> serviceNames = new HashSet<>();
        services.forEach(s -> serviceNames.add(s.get("name").asString()));
        for (StandardJobPack.Job job : pack.jobs()) {
            JsonNode c = job.columns();
            assertThat(jobTypes).as(job.code()).contains(c.get("job_type").asString());
            assertThat(c.get("cycle_types").isArray() && !c.get("cycle_types").isEmpty()).as(job.code()).isTrue();
            c.get("cycle_types").forEach(t -> CycleType.parse(t.asString()));
            EodPolicy.FailurePolicy.of(c.get("failure_policy").asString());
            assertThat(serviceNames).as(job.code()).contains(job.service());
            assertThat(c.get("name").asString().length()).isBetween(3, 160);
            assertThat(c.get("timeout_minutes").asInt()).isBetween(1, 1440);
            assertThat(c.get("max_retries").asInt()).isBetween(0, 10);
            assertThat(job.stage()).as(job.code()).isNotBlank();
            // a job whose path needs tenant parameters ships inactive and declares them
            List<String> pathParams = JobTemplates.pathParameters(job.path());
            assertThat(job.requiredParameters()).as(job.code()).containsExactlyInAnyOrderElementsOf(pathParams);
            if (!pathParams.isEmpty()) {
                assertThat(job.active()).as(job.code() + " needs parameters, must ship inactive").isFalse();
            }
        }
    }

    @Test
    void dependencyGraphIsAcyclicAndInBankingOrder() {
        JobGraph g = pack.graph();
        assertThat(g.validate()).isEmpty();
        List<String> order = g.topologicalOrder();
        assertThat(order).hasSize(pack.jobs().size());
        // card / payments cut-off -> postings -> accruals -> delinquency -> collections -> fees -> ledger/GL
        // -> treasury/liquidity -> reporting/statements/notifications
        assertBefore(g, "CARD_AUTH_EXPIRY", "DEP_INTEREST_ACCRUAL");
        assertBefore(g, "PAYMENT_EXECUTE_DUE", "DEP_STANDING_ORDERS");
        assertBefore(g, "DEP_STANDING_ORDERS", "LOAN_AUTO_DEBIT");
        assertBefore(g, "LOAN_AUTO_DEBIT", "DEP_INTEREST_ACCRUAL");
        assertBefore(g, "LOAN_AUTO_DEBIT", "LOAN_INTEREST_ACCRUAL");
        assertBefore(g, "LOAN_INTEREST_ACCRUAL", "LOAN_DELINQUENCY");
        assertBefore(g, "LOAN_DELINQUENCY", "COLLECTIONS_STRATEGY_RUN");
        assertBefore(g, "DEP_INTEREST_ACCRUAL", "DEP_DORMANCY");
        assertBefore(g, "COLLECTIONS_STRATEGY_RUN", "LEDGER_EOD");
        assertBefore(g, "DEP_DORMANCY", "LEDGER_EOD");
        assertBefore(g, "LEDGER_EOD", "GL_ECL_PROVISION");
        assertBefore(g, "LEDGER_EOD", "LIQ_POSITION_SNAPSHOT");
        assertBefore(g, "TRY_ACCRUALS", "TRY_MARK_TO_MARKET");
        assertBefore(g, "LIQ_POSITION_SNAPSHOT", "REG_REPORT_RUN_DUE");
        assertBefore(g, "LEDGER_EOD", "STATEMENT_CYCLE");
        assertBefore(g, "REG_REPORT_RUN_DUE", "ANALYTICS_DATA_SHARES");
        // stages never point backwards
        Map<String, Integer> seq = new TreeMap<>();
        pack.jobs().forEach(j -> seq.put(j.code(), j.columns().get("sequence").asInt()));
        for (StandardJobPack.Job j : pack.jobs()) {
            for (String d : j.dependsOn()) {
                assertThat(seq.get(d)).as(j.code() + " depends on " + d).isLessThanOrEqualTo(seq.get(j.code()));
            }
        }
    }

    @Test
    void dailyPlanKeepsTheOrderThroughInactiveAndMonthEndJobs() {
        // the EOD plan without the EOM-only ECL job still orders the ledger close and reporting after accruals
        Set<String> eod = pack.jobs().stream().filter(StandardJobPack.Job::active)
                .filter(j -> j.columns().get("cycle_types").toString().contains("\"EOD\""))
                .map(StandardJobPack.Job::code).collect(Collectors.toSet());
        assertThat(eod).doesNotContain("GL_ECL_PROVISION").contains("LEDGER_EOD", "STATEMENT_CYCLE");
        JobGraph daily = pack.graph().restrictTo(eod);
        assertBefore(daily, "DEP_INTEREST_ACCRUAL", "LEDGER_EOD");
        assertBefore(daily, "LEDGER_EOD", "STATEMENT_CYCLE");
        assertBefore(daily, "DEP_INTEREST_ACCRUAL", "STATEMENT_CYCLE");
        assertBefore(daily, "COLLECTIONS_STRATEGY_RUN", "LIQ_POSITION_SNAPSHOT");
        assertBefore(daily, "LOAN_DELINQUENCY", "REG_REPORT_RUN_DUE");
        assertThat(daily.levels().size()).isGreaterThanOrEqualTo(6);
    }

    @Test
    void ledgerEodRunsTenantWideWithoutParametersAndKeepsAnOptionalBook() {
        StandardJobPack.Job ledger = pack.jobs().stream().filter(j -> j.code().equals("LEDGER_EOD")).findFirst().orElseThrow();
        assertThat(ledger.path()).isEqualTo("/v1/ledgers/end-of-day");
        assertThat(ledger.active()).isTrue();
        assertThat(ledger.requiredParameters()).isEmpty();
        assertThat(pack.jobs().stream().filter(j -> !j.requiredParameters().isEmpty()).map(StandardJobPack.Job::code))
                .as("no standard job needs tenant parameters any more").isEmpty();
        // legal entity of the EOD run; a tenant that pinned one book keeps it through parameters.ledger_id
        JsonNode body = JobTemplates.renderBody(ledger.columns().get("body_template"), JSON.readTree("""
                {"business_date":"2026-10-06","legal_entity_id":"e1","parameters":{"ledger_id":"L1"}}"""));
        assertThat(body.toString()).isEqualTo("{\"business_date\":\"2026-10-06\",\"legal_entity_id\":\"e1\",\"ledger_id\":\"L1\"}");
        JsonNode wide = JobTemplates.renderBody(ledger.columns().get("body_template"), JSON.readTree("""
                {"business_date":"2026-10-06","legal_entity_id":"e1","parameters":{}}"""));
        assertThat(wide.get("ledger_id").isNull()).isTrue();

        // upgrade from the per-book definition: activated only when it waited for the missing ledger_id
        JsonNode legacyWaiting = JSON.readTree("""
                {"is_active":false,"http_path":"/v1/ledgers/{parameters.ledger_id}/eod","parameters":{"ledger_id":null}}""");
        JsonNode legacyOff = JSON.readTree("""
                {"is_active":false,"http_path":"/v1/ledgers/{parameters.ledger_id}/eod","parameters":{"ledger_id":"L1"}}""");
        JsonNode legacyOn = JSON.readTree("""
                {"is_active":true,"http_path":"/v1/ledgers/{parameters.ledger_id}/eod","parameters":{"ledger_id":"L1"}}""");
        assertThat(JobPackInstaller.waitedOnlyForParameters(ledger, legacyWaiting)).isTrue();
        assertThat(JobPackInstaller.waitedOnlyForParameters(ledger, legacyOff)).isFalse();
        assertThat(JobPackInstaller.waitedOnlyForParameters(ledger, legacyOn)).isFalse();
    }

    @Test
    void parameterPlaceholdersRender() {
        JsonNode ctx = JSON.readTree("""
                {"business_date":"2026-10-06","legal_entity_id":"e1","parameters":{"ledger_id":"L 1"}}""");
        assertThat(JobTemplates.renderPath("/v1/ledgers/{parameters.ledger_id}/eod?d={business_date}", ctx))
                .isEqualTo("/v1/ledgers/L+1/eod?d=2026-10-06");
        JsonNode body = JobTemplates.renderBody(JSON.readTree("""
                {"date":"{business_date}","acc":"{parameters.missing}","l":"{parameters.ledger_id}"}"""), ctx);
        assertThat(body.get("date").asString()).isEqualTo("2026-10-06");
        assertThat(body.get("acc").isNull()).isTrue();
        assertThat(body.get("l").asString()).isEqualTo("L 1");
        assertThat(JobTemplates.pathParameters("/x/{parameters.a}/{business_date}/{parameters.b}")).containsExactly("a", "b");
    }

    // ------------------------------------------------------------------ helpers

    static void assertBefore(JobGraph g, String first, String then) {
        assertThat(g.downstreamOf(first)).as(first + " must run before " + then).contains(then);
    }

    /** A generated route path (with {var} segments) matches a pack path (with {placeholder} segments). */
    static boolean pathMatches(String route, String pack) {
        String[] a = route.split("/");
        String[] b = pack.split("/");
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            boolean routeVar = a[i].startsWith("{");
            boolean packVar = b[i].startsWith("{");
            if (packVar && !routeVar) {
                return false;
            }
            if (!routeVar && !a[i].equals(b[i])) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> serviceSpec(String service) {
        LoaderOptions opts = new LoaderOptions();
        opts.setCodePointLimit(64 * 1024 * 1024);
        opts.setMaxAliasesForCollections(100_000);
        Path file = root.resolve("generated/openapi/services/" + service + ".openapi.yaml");
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new Yaml(opts).load(r);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> operation(Map<String, Object> spec, String method, String path) {
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        for (Map.Entry<String, Object> e : paths.entrySet()) {
            if (pathMatches(e.getKey(), path)) {
                Map<String, Object> ops = (Map<String, Object>) e.getValue();
                if (ops.containsKey(method)) {
                    return (Map<String, Object>) ops.get(method);
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> parameters(Map<String, Object> spec, Map<String, Object> op) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object p : (List<Object>) op.getOrDefault("parameters", List.of())) {
            Map<String, Object> m = (Map<String, Object>) p;
            if (m.containsKey("$ref")) {
                String name = ((String) m.get("$ref")).substring("#/components/parameters/".length());
                m = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) spec.get("components"))
                        .get("parameters")).get(name);
            }
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<String> requiredBodyFields(Map<String, Object> spec, Map<String, Object> op) {
        Map<String, Object> rb = (Map<String, Object>) op.get("requestBody");
        if (rb == null) {
            return List.of();
        }
        Map<String, Object> sch = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) rb.get("content"))
                .get("application/json")).get("schema");
        if (sch.containsKey("$ref")) {
            sch = schema(spec, ((String) sch.get("$ref")).substring("#/components/schemas/".length()));
        }
        return (List<String>) sch.getOrDefault("required", List.of());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> schema(Map<String, Object> spec, String name) {
        return (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) spec.get("components"))
                .get("schemas")).get(name);
    }
}
