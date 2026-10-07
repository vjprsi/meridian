package io.meridian.regulatoryreporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.meridian.regulatoryreporting.application.RegulatoryPackCatalog;
import io.meridian.regulatoryreporting.application.SourceCollector;
import io.meridian.regulatoryreporting.domain.DefinitionException;
import io.meridian.regulatoryreporting.domain.Expr;
import io.meridian.regulatoryreporting.domain.FilingCalendar;
import io.meridian.regulatoryreporting.domain.PathTemplate;
import io.meridian.regulatoryreporting.domain.RegulatoryPack;
import io.meridian.regulatoryreporting.domain.ReportEngine;
import io.meridian.regulatoryreporting.domain.ReportValidator;
import io.meridian.regulatoryreporting.domain.SourceTransforms;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every shipped regulatory pack against the generated contracts and its own sample data:
 * <ul>
 *   <li>every SERVICE data source and parameter resolver calls an existing GET route of an allowed service
 *       (generated/routes.json) with known query parameters and enum values;</li>
 *   <li>every field a mapping, filter, ranking or COMBINE expression reads exists in the source's response schema
 *       (generated/openapi), GL-mapping attributes exist in the pack's mapping table;</li>
 *   <li>sources, mappings and validation rules compile (expressions parse, rules reference existing cells, derived
 *       cells are acyclic);</li>
 *   <li>filing calendars parse and produce the expected due dates (business-day conventions and holidays);</li>
 *   <li>each report computed on the pack's sample data yields the cells and findings its {@code sample.expect} lists.</li>
 * </ul>
 */
class RegulatoryPackDefinitionTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Pattern QUERY_PLACEHOLDER = Pattern.compile("\\{[a-z_.]+}");
    static List<RegulatoryPack> packs;
    static JsonNode routes;
    static Path root;
    static final Map<String, Map<String, Object>> specs = new HashMap<>();

    @BeforeAll
    static void load() throws IOException {
        packs = RegulatoryPackCatalog.load(JSON, RegulatoryPackCatalog.LOCATION);
        try (InputStream in = RegulatoryPackDefinitionTest.class.getClassLoader().getResourceAsStream("meridian/routes.json")) {
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
    void shipsKenyaUnitedKingdomAndNigeriaWithTheRequiredReportFamilies() {
        Map<String, RegulatoryPack> byCode = new LinkedHashMap<>();
        packs.forEach(p -> byCode.put(p.code(), p));
        assertThat(byCode).containsOnlyKeys("KE_CBK", "GB_PRA", "NG_CBN");
        Set<String> prudential = Set.of("CAPITAL", "LIQUIDITY", "LARGE_EXPOSURE", "BALANCE_SHEET", "COMPLAINTS");
        assertThat(families(byCode.get("KE_CBK"))).containsAll(prudential).contains("AML_CTR", "MOBILE_MONEY");
        assertThat(families(byCode.get("NG_CBN"))).containsAll(prudential).contains("AML_CTR", "MOBILE_MONEY");
        assertThat(families(byCode.get("GB_PRA"))).containsAll(prudential).contains("AML_SAR");
        for (RegulatoryPack pack : packs) {
            assertThat(pack.version()).matches("\\d{4}\\.\\d{1,2}\\.\\d+");
            assertThat(pack.notice()).contains("ILLUSTRATIVE");
            assertThat(pack.document().path("calendar").path("holidays").size()).as(pack.code() + " holidays").isGreaterThan(5);
            Set<String> formats = new HashSet<>();
            pack.reports().forEach(r -> formats.add(r.definition().get("format").asString()));
            if ("GB".equals(pack.country())) {
                assertThat(formats).as("UK reports are XBRL-like instances").containsOnly("XBRL");
                pack.reports().forEach(r -> assertThat(r.version().path("xbrl_taxonomy").asString()).contains("placeholder"));
            } else {
                assertThat(formats).as(pack.code() + " uses CSV / XML").isSubsetOf("CSV", "XML");
            }
            // KE and NG report liquidity both as LCR / NSFR and as the local statutory ratio
            pack.reports().stream().filter(r -> "LIQUIDITY".equals(r.definition().get("report_family").asString())
                    && !"GB".equals(pack.country())).forEach(r -> assertThat(r.version().get("data_sources").toString())
                    .contains("/v1/treasury/liquidity/lcr").contains("/v1/treasury/liquidity/nsfr").contains("trial-balance"));
            Set<String> checksums = new HashSet<>();
            pack.reports().forEach(r -> assertThat(checksums.add(r.versionChecksum())).as(r.code()).isTrue());
        }
    }

    @Test
    void sourcesMappingsAndRulesCompile() {
        for (RegulatoryPack pack : packs) {
            for (RegulatoryPack.Report r : pack.reports()) {
                assertThatCode(() -> compile(r)).as(pack.code() + " " + r.code()).doesNotThrowAnyException();
                assertThat(FilingCalendar.CONVENTIONS).contains(r.definition().get("business_day_convention").asString());
                assertThatCode(() -> FilingCalendar.parse(r.definition().path("deadline_rule").asString(null),
                        r.definition().get("frequency").asString())).as(r.code()).doesNotThrowAnyException();
                Set<String> codes = new HashSet<>();
                r.version().path("validation_rules").forEach(v -> assertThat(codes.add(v.get("code").asString()))
                        .as(r.code() + " rule " + v.get("code")).isTrue());
                assertThat(codes).as(r.code() + " has cross-cell checks").isNotEmpty();
            }
        }
    }

    @Test
    void everyServiceSourceCallsAnExistingRouteWithKnownParameters() {
        for (RegulatoryPack pack : packs) {
            for (RegulatoryPack.Report r : pack.reports()) {
                for (JsonNode ds : r.version().get("data_sources")) {
                    if (!"SERVICE".equals(ds.path("type").asString("SERVICE"))) {
                        continue;
                    }
                    checkCall(r.code() + "/" + ds.get("name").asString(), ds.get("service").asString(), ds.get("path").asString());
                    ds.path("resolve").properties().forEach(e -> checkCall(r.code() + " resolver " + e.getKey(),
                            e.getValue().get("service").asString(), e.getValue().get("path").asString()));
                }
            }
        }
    }

    @Test
    void everyMappedFieldExistsInTheSourceSchemas() {
        for (RegulatoryPack pack : packs) {
            for (RegulatoryPack.Report r : pack.reports()) {
                Map<String, Set<String>> fields = new LinkedHashMap<>();
                for (JsonNode ds : r.version().get("data_sources")) {
                    String name = ds.get("name").asString();
                    String type = ds.path("type").asString("SERVICE");
                    Set<String> f = new TreeSet<>();
                    if ("SERVICE".equals(type)) {
                        f.addAll(responseFields(ds.get("service").asString(), ds.get("path").asString(),
                                ds.path("rows_pointer").asString("/data")));
                        if (ds.hasNonNull("id_field")) {
                            assertThat(f).as(r.code() + "/" + name + " id_field").contains(ds.get("id_field").asString());
                        }
                        if (ds.has("gl_mapping")) {
                            assertThat(f).as(r.code() + "/" + name + " account field").contains(ds.at("/gl_mapping/account_field").asString());
                            f.add(SourceTransforms.GL_MAP);
                        }
                    } else if ("COMBINE".equals(type)) {
                        f.add(ds.path("key_field").asString("key"));
                        f.add("member_count");
                        f.add("members");
                        for (JsonNode part : ds.get("from")) {
                            Set<String> partFields = fields.get(part.get("source").asString());
                            assertThat(partFields).as(r.code() + " COMBINE part " + part.get("source")).isNotNull();
                            String at = r.code() + "/" + name + " <- " + part.get("source").asString();
                            checkIdentifiers(at, part.get("key").asString(), partFields, ds, r);
                            if (part.hasNonNull("filter")) {
                                checkIdentifiers(at, part.get("filter").asString(), partFields, ds, r);
                            }
                            for (String group : List.of("sum", "first")) {
                                part.path(group).properties().forEach(e -> {
                                    f.add(e.getKey());
                                    checkIdentifiers(at + "." + e.getKey(), e.getValue().asString(), partFields, ds, r);
                                });
                            }
                        }
                    }
                    fields.put(name, f);
                }
                for (JsonNode m : r.version().get("field_mappings")) {
                    if (!m.hasNonNull("source")) {
                        continue;
                    }
                    JsonNode ds = source(r, m.get("source").asString());
                    Set<String> f = fields.get(m.get("source").asString());
                    for (String e : List.of("value", "filter", "order_by")) {
                        if (m.hasNonNull(e)) {
                            checkIdentifiers(r.code() + "." + m.get("cell_ref").asString() + "." + e, m.get(e).asString(), f, ds, r);
                        }
                    }
                }
            }
        }
    }

    @Test
    void filingCalendarsApplyDeadlinesConventionsAndHolidays() {
        // KE capital return: 2026-09-30 + 10 days = Saturday 2026-10-10 (also a holiday) -> Monday 2026-10-12
        assertThat(due("KE_CBK", "KE-CBK-CA1", "2026-09-30")).isEqualTo("2026-10-12");
        // KE FRC return: 7 business days after Saturday 2026-10-31
        assertThat(due("KE_CBK", "KE-FRC-AML1", "2026-10-31")).isEqualTo("2026-11-10");
        // UK quarterly COREP-like: quarter end + 42 days = 12 May
        assertThat(due("GB_PRA", "GB-PRA-OF1", "2026-03-31")).isEqualTo("2026-05-12");
        // UK half-yearly complaints: 2026-12-31 + 30 days = Saturday 2027-01-30 -> Monday 2027-02-01
        assertThat(due("GB_PRA", "GB-FCA-CMP1", "2026-12-31")).isEqualTo("2027-02-01");
        // NG monthly balance sheet: 5 business days, 1 October (Independence Day) is not one -> 8 October
        assertThat(due("NG_CBN", "NG-CBN-MBR1", "2026-09-30")).isEqualTo("2026-10-08");
        // conventions
        FilingCalendar.Conventions preceding = new FilingCalendar.Conventions("PRECEDING", Set.of(LocalDate.parse("2026-10-09")));
        assertThat(preceding.adjust(LocalDate.parse("2026-10-10"))).isEqualTo(LocalDate.parse("2026-10-08"));
        FilingCalendar.Conventions modified = new FilingCalendar.Conventions("MODIFIED_FOLLOWING", Set.of());
        assertThat(modified.adjust(LocalDate.parse("2026-10-31"))).isEqualTo(LocalDate.parse("2026-10-30"));
        assertThat(modified.adjust(LocalDate.parse("2026-10-10"))).isEqualTo(LocalDate.parse("2026-10-12"));
        assertThat(new FilingCalendar.Conventions("NONE", Set.of()).adjust(LocalDate.parse("2026-10-10")))
                .isEqualTo(LocalDate.parse("2026-10-10"));
    }

    @Test
    void sampleDataProducesTheExpectedCellsAndFindings() {
        int checked = 0;
        for (RegulatoryPack pack : packs) {
            JsonNode sampleData = pack.document().get("sample_data");
            assertThat(sampleData).as(pack.code() + " sample_data").isNotNull();
            Map<String, Object> params = new LinkedHashMap<>(JSON.convertValue(sampleData.path("parameters"),
                    new TypeReference<Map<String, Object>>() {}));
            for (RegulatoryPack.Report r : pack.reports()) {
                assertThat(r.sample()).as(r.code() + " sample").isNotNull();
                Compiled c = compile(r);
                Map<String, ReportEngine.Source> sources = new LinkedHashMap<>();
                for (JsonNode ds : r.version().get("data_sources")) {
                    String name = ds.get("name").asString();
                    List<Map<String, Object>> rows;
                    if ("COMBINE".equals(ds.path("type").asString("SERVICE"))) {
                        List<DefinitionException.Problem> problems = new ArrayList<>();
                        SourceTransforms.Combine combine = SourceTransforms.combine(map(ds), sources.keySet(), name, problems);
                        assertThat(problems).isEmpty();
                        rows = SourceTransforms.applyCombine(combine, sources, params);
                    } else {
                        assertThat(sampleData.has(name)).as(pack.code() + " sample_data." + name).isTrue();
                        rows = JSON.convertValue(sampleData.get(name), new TypeReference<List<Map<String, Object>>>() {});
                        if (ds.has("gl_mapping")) {
                            List<DefinitionException.Problem> problems = new ArrayList<>();
                            SourceTransforms.GlMapping mapping = SourceTransforms.glMapping(map(ds).get("gl_mapping"), name, problems);
                            assertThat(problems).isEmpty();
                            rows = SourceTransforms.applyGlMapping(mapping, rows);
                        }
                    }
                    sources.put(name, new ReportEngine.Source(name, ds.path("id_field").asString(null), rows));
                }
                List<ReportEngine.Cell> cells = ReportEngine.compute(c.mappings(), sources, params);
                Map<String, Object> values = new LinkedHashMap<>();
                Map<String, String> text = new LinkedHashMap<>();
                cells.forEach(cell -> {
                    values.put(cell.cellRef(), cell.typedValue());
                    text.put(cell.cellRef(), cell.value());
                });
                r.sample().path("expect").path("cells").properties().forEach(e ->
                        assertThat(text.get(e.getKey())).as(pack.code() + " " + r.code() + " " + e.getKey()).isEqualTo(e.getValue().asString()));
                Set<String> findings = new TreeSet<>();
                ReportValidator.validate(c.rules(), values, params).forEach(f -> findings.add(f.ruleCode()));
                Set<String> expected = new TreeSet<>();
                r.sample().path("expect").path("findings").forEach(f -> expected.add(f.asString()));
                assertThat(findings).as(pack.code() + " " + r.code() + " findings").isEqualTo(expected);
                checked++;
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(20);
    }

    @Test
    void glMappingTablesAreConsistentAndOverridable() {
        for (RegulatoryPack pack : packs) {
            assertThat(pack.glTables()).as(pack.code()).hasSize(1);
            pack.glTables().forEach((name, table) -> {
                List<DefinitionException.Problem> problems = new ArrayList<>();
                List<SourceTransforms.GlRule> rules = SourceTransforms.glRules(JSON.convertValue(table.get("rules"),
                        new TypeReference<List<Object>>() {}), name, "pack", problems);
                assertThat(problems).as(name).isEmpty();
                // ranges of the reference chart do not overlap (first match would hide a later rule)
                for (int i = 0; i < rules.size(); i++) {
                    for (int j = i + 1; j < rules.size(); j++) {
                        SourceTransforms.GlRule a = rules.get(i);
                        SourceTransforms.GlRule b = rules.get(j);
                        boolean overlap = a.from().compareTo(b.to()) <= 0 && b.from().compareTo(a.to()) <= 0;
                        assertThat(overlap).as(name + " " + a.from() + "-" + a.to() + " vs " + b.from() + "-" + b.to()).isFalse();
                    }
                }
                // a tenant rule (config-service override) wins over the pack's
                SourceTransforms.GlMapping m = new SourceTransforms.GlMapping(name, "gl_account_code", "account_class", rules);
                List<SourceTransforms.GlRule> tenant = SourceTransforms.glRules(List.of(Map.of("line", "TENANT_LINE", "code", "1000",
                        "attributes", Map.of("risk_weight", "0.5"))), "override", "tenant", problems);
                List<Map<String, Object>> mapped = SourceTransforms.applyGlMapping(m.withOverride(tenant, false),
                        List.of(Map.of("gl_account_code", "1000", "account_class", "ASSET"), Map.of("gl_account_code", "1010", "account_class", "ASSET"),
                                Map.of("gl_account_code", "9999", "account_class", "LIABILITY")));
                assertThat(ReportEngine.lookup(mapped.get(0), "gl_map.line")).isEqualTo("TENANT_LINE");
                assertThat(ReportEngine.lookup(mapped.get(0), "gl_map.origin")).isEqualTo("tenant");
                assertThat(ReportEngine.lookup(mapped.get(1), "gl_map.origin")).isEqualTo("pack");
                assertThat(ReportEngine.lookup(mapped.get(2), "gl_map.line")).isNull();
                assertThat(ReportEngine.lookup(mapped.get(2), "gl_map.sign").toString()).isEqualTo("-1");
            });
        }
    }

    // ------------------------------------------------------------------ helpers

    record Compiled(List<ReportEngine.Mapping> mappings, List<ReportValidator.Rule> rules) {}

    static Compiled compile(RegulatoryPack.Report r) {
        List<Map<String, Object>> sources = JSON.convertValue(r.version().get("data_sources"), new TypeReference<>() {});
        Set<String> names = ReportEngine.checkSources(sources, SourceCollector.ALLOWED_SERVICES);
        List<ReportEngine.Mapping> mappings = ReportEngine.compile(JSON.convertValue(r.version().get("field_mappings"),
                new TypeReference<List<Map<String, Object>>>() {}), names);
        Set<String> refs = new LinkedHashSet<>();
        mappings.forEach(m -> refs.add(m.cellRef()));
        List<ReportValidator.Rule> rules = ReportValidator.compile(JSON.convertValue(r.version().get("validation_rules"),
                new TypeReference<List<Map<String, Object>>>() {}), refs);
        return new Compiled(mappings, rules);
    }

    static Set<String> families(RegulatoryPack p) {
        Set<String> out = new HashSet<>();
        p.reports().forEach(r -> out.add(r.definition().get("report_family").asString()));
        return out;
    }

    static String due(String pack, String report, String periodEnd) {
        RegulatoryPack p = packs.stream().filter(x -> x.code().equals(pack)).findFirst().orElseThrow();
        RegulatoryPack.Report r = p.report(report);
        Set<LocalDate> holidays = new HashSet<>();
        r.definition().get("holidays").forEach(h -> holidays.add(LocalDate.parse(h.asString())));
        return FilingCalendar.dueDate(r.definition().path("deadline_rule").asString(null), r.definition().get("frequency").asString(),
                LocalDate.parse(periodEnd), r.definition().path("filing_offset_days").asInt(0),
                new FilingCalendar.Conventions(r.definition().get("business_day_convention").asString(), holidays)).toString();
    }

    static JsonNode source(RegulatoryPack.Report r, String name) {
        for (JsonNode ds : r.version().get("data_sources")) {
            if (ds.get("name").asString().equals(name)) {
                return ds;
            }
        }
        throw new AssertionError(r.code() + " has no source " + name);
    }

    static Map<String, Object> map(JsonNode n) {
        return JSON.convertValue(n, new TypeReference<Map<String, Object>>() {});
    }

    /** Identifiers an expression reads must be fields of the source (or GL mapping attributes, or run parameters). */
    static void checkIdentifiers(String at, String expression, Set<String> fields, JsonNode ds, RegulatoryPack.Report r) {
        for (String id : Expr.parse(expression).identifiers()) {
            if (id.startsWith("param_") || id.startsWith("param.")) {
                continue;
            }
            String head = id.contains(".") ? id.substring(0, id.indexOf('.')) : id;
            assertThat(fields).as(at + " reads " + id).contains(head);
            if (SourceTransforms.GL_MAP.equals(head)) {
                String attr = id.substring(id.indexOf('.') + 1);
                Set<String> known = new HashSet<>(Set.of("line", "sign", "table", "origin"));
                ds.at("/gl_mapping/rules").forEach(rule -> rule.path("attributes").properties().forEach(e -> known.add(e.getKey())));
                assertThat(known).as(at + " GL mapping attribute " + attr).contains(attr);
            }
        }
    }

    static void checkCall(String at, String service, String pathWithQuery) {
        assertThat(SourceCollector.ALLOWED_SERVICES).as(at).contains(service);
        assertThat(PathTemplate.isSafe(pathWithQuery)).as(at + " safe path").isTrue();
        assertThatCode(() -> PathTemplate.check(pathWithQuery)).as(at).doesNotThrowAnyException();
        String path = pathWithQuery.contains("?") ? pathWithQuery.substring(0, pathWithQuery.indexOf('?')) : pathWithQuery;
        List<JsonNode> matches = new ArrayList<>();
        routes.forEach(rt -> {
            if ("GET".equals(rt.get("method").asString()) && pathMatches(rt.get("path").asString(), path)) {
                matches.add(rt);
            }
        });
        // a literal segment beats a path variable (/summary vs /{id}), as in Spring's routing
        int best = matches.stream().mapToInt(rt -> literals(rt.get("path").asString())).max().orElse(0);
        matches.removeIf(rt -> literals(rt.get("path").asString()) < best);
        assertThat(matches).as(at + " route GET " + path).hasSize(1);
        assertThat(matches.getFirst().get("service").asString()).as(at).isEqualTo(service);
        Map<String, Object> op = operation(spec(service), path);
        assertThat(op).as(at + " operation").isNotNull();
        Map<String, Map<String, Object>> params = new LinkedHashMap<>();
        for (Map<String, Object> p : parameters(spec(service), op)) {
            if ("query".equals(p.get("in"))) {
                params.put((String) p.get("name"), p);
            }
        }
        Set<String> supplied = new HashSet<>();
        if (pathWithQuery.contains("?")) {
            for (String kv : pathWithQuery.substring(pathWithQuery.indexOf('?') + 1).split("&")) {
                String[] parts = kv.split("=", 2);
                supplied.add(parts[0]);
                assertThat(params).as(at + " query parameter " + parts[0]).containsKey(parts[0]);
                String value = parts.length > 1 ? parts[1] : "";
                @SuppressWarnings("unchecked")
                Map<String, Object> schema = (Map<String, Object>) params.get(parts[0]).get("schema");
                Map<String, Object> resolved = resolve(spec(service), schema);
                if (resolved != null && resolved.get("enum") instanceof List<?> values && !QUERY_PLACEHOLDER.matcher(value).find()) {
                    assertThat(values.stream().map(String::valueOf).toList()).as(at + " " + parts[0]).contains(value);
                }
            }
        }
        params.forEach((name, p) -> {
            if (Boolean.TRUE.equals(p.get("required"))) {
                assertThat(supplied).as(at + " required query parameter").contains(name);
            }
        });
    }

    /** Field names of the rows a source reads at its rows pointer. */
    @SuppressWarnings("unchecked")
    static Set<String> responseFields(String service, String pathWithQuery, String pointer) {
        String path = pathWithQuery.contains("?") ? pathWithQuery.substring(0, pathWithQuery.indexOf('?')) : pathWithQuery;
        Map<String, Object> spec = spec(service);
        Map<String, Object> op = operation(spec, path);
        Map<String, Object> content = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) op.get("responses"))
                .get("200")).get("content");
        Map<String, Object> schema = resolve(spec, (Map<String, Object>) ((Map<String, Object>) content.values().iterator().next()).get("schema"));
        Set<String> out = new TreeSet<>();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        String field = pointer.substring(1);
        if (props != null && props.get(field) != null) {
            Map<String, Object> arr = resolve(spec, (Map<String, Object>) props.get(field));
            Map<String, Object> items = resolve(spec, (Map<String, Object>) arr.get("items"));
            out.addAll(((Map<String, Object>) items.get("properties")).keySet());
            return out;
        }
        // computed object responses document their rows in the summary: "{groups[{a, b, c}]}" or the trial balance
        if (path.startsWith("/v1/gl/periods/") && path.endsWith("/trial-balance") && "/lines".equals(pointer)) {
            Map<String, Object> balance = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) spec.get("components"))
                    .get("schemas")).get("GlBalance");
            out.addAll(((Map<String, Object>) balance.get("properties")).keySet());
            out.addAll(List.of("gl_account_code", "gl_account_name", "account_class"));
            return out;
        }
        Matcher m = Pattern.compile(Pattern.quote(field) + "\\[\\{([^}]*)}]").matcher(String.valueOf(op.get("summary")).replaceAll("\\s+", " "));
        assertThat(m.find()).as(service + " " + path + " documents " + pointer).isTrue();
        for (String f : m.group(1).split(",")) {
            out.add(f.trim());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> resolve(Map<String, Object> spec, Map<String, Object> schema) {
        Map<String, Object> s = schema;
        while (s != null && s.containsKey("$ref")) {
            Object node = spec;
            for (String part : ((String) s.get("$ref")).substring(2).split("/")) {
                node = ((Map<String, Object>) node).get(part);
            }
            s = (Map<String, Object>) node;
        }
        return s;
    }

    static boolean pathMatches(String route, String path) {
        String[] a = route.split("/");
        String[] b = path.split("/");
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            boolean routeVar = a[i].startsWith("{");
            boolean packVar = b[i].startsWith("{");
            if (packVar != routeVar && !routeVar) {
                return false;
            }
            if (!routeVar && !a[i].equals(b[i])) {
                return false;
            }
        }
        return true;
    }

    static Map<String, Object> spec(String service) {
        return specs.computeIfAbsent(service, s -> {
            LoaderOptions opts = new LoaderOptions();
            opts.setCodePointLimit(64 * 1024 * 1024);
            opts.setMaxAliasesForCollections(100_000);
            Path file = root.resolve("generated/openapi/services/" + s + ".openapi.yaml");
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return new Yaml(opts).load(r);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + file, e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> operation(Map<String, Object> spec, String path) {
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> best = null;
        int bestScore = -1;
        for (Map.Entry<String, Object> e : paths.entrySet()) {
            if (pathMatches(e.getKey(), path)) {
                Map<String, Object> ops = (Map<String, Object>) e.getValue();
                if (ops.containsKey("get") && literals(e.getKey()) > bestScore) {
                    best = (Map<String, Object>) ops.get("get");
                    bestScore = literals(e.getKey());
                }
            }
        }
        return best;
    }

    static int literals(String route) {
        int n = 0;
        for (String s : route.split("/")) {
            if (!s.startsWith("{")) {
                n++;
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> parameters(Map<String, Object> spec, Map<String, Object> op) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object p : (List<Object>) op.getOrDefault("parameters", List.of())) {
            out.add(resolve(spec, (Map<String, Object>) p));
        }
        return out;
    }
}
