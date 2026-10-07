package io.meridian.regulatoryreporting.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A versioned regulatory pack shipped as data ({@code regulatory-packs/*.yaml}): the report definitions of one market
 * and regulator, each with a version (data sources, field mappings, validation rules), the filing calendar (deadline
 * rule, jurisdiction offset, business-day convention, the regulator's holidays) and GL mapping tables.
 *
 * <p>Packs reuse declarations: a data source with {@code use: <template>} starts from the pack's
 * {@code source_templates} entry (its own fields win), and {@code gl_mapping: <TABLE>} is replaced by the pack's table
 * ({@code {table, account_field, rules}}), so each installed version is self-contained and immutable. The checksums
 * (definition-level fields; version content) tell an upgrade what changed.
 *
 * <p>Report layouts (cell codes, rows, columns) are ILLUSTRATIVE samples in the shape of the regulator's returns,
 * not copies of its forms: validate them against the regulator's current taxonomy before use.
 */
public record RegulatoryPack(String code, String version, String country, String regulator, String currency,
                             String title, String notice, JsonNode calendar, Map<String, JsonNode> glTables,
                             List<Report> reports, JsonNode document) {

    public static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");
    public static final Pattern VERSION = Pattern.compile("\\d{4}\\.\\d{1,2}\\.\\d{1,4}");
    /** Definition columns owned by the pack: an upgrade resets them (filing_offset_days and owner stay tenant-tuned). */
    public static final List<String> MANAGED = List.of("name", "country_pack", "jurisdiction", "regulator", "report_family",
            "frequency", "format", "deadline_rule", "business_day_convention", "holidays", "description");
    /** Version content covered by the version checksum (effective dates and notes alone do not make a new version). */
    static final List<String> VERSION_CONTENT = List.of("data_sources", "field_mappings", "validation_rules", "xbrl_taxonomy");

    /**
     * One report of the pack.
     *
     * @param definition report_definition columns (managed fields plus filing_offset_days and owner defaults)
     * @param version the version request (effective_from, change_note, data_sources expanded, field_mappings, ...)
     * @param sample sample source rows and expected cells / findings (documentation and tests), may be null
     */
    public record Report(String code, ObjectNode definition, ObjectNode version, JsonNode sample,
                         String definitionChecksum, String versionChecksum) {}

    /** A pack that fails structural checks. */
    public static final class InvalidPack extends RuntimeException {
        private final List<String> problems;

        public InvalidPack(String pack, List<String> problems) {
            super("regulatory pack " + pack + " is invalid: " + String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    public Report report(String code) {
        return reports.stream().filter(r -> r.code().equals(code)).findFirst().orElse(null);
    }

    /** Parses a pack document (YAML loaded as a JSON tree); throws {@link InvalidPack} listing every problem. */
    public static RegulatoryPack parse(JsonNode doc) {
        List<String> problems = new ArrayList<>();
        String code = doc.path("pack_code").asString("");
        String version = doc.path("version").asString("");
        if (!CODE.matcher(code).matches()) {
            problems.add("pack_code must match " + CODE.pattern());
        }
        if (!VERSION.matcher(version).matches()) {
            problems.add("version must be YYYY.M.N");
        }
        String country = doc.path("country").asString("");
        if (!country.matches("[A-Z]{2}")) {
            problems.add("country must be an ISO 3166 alpha-2 code");
        }
        String regulator = doc.path("regulator").asString("");
        if (regulator.isBlank() || regulator.length() > 64) {
            problems.add("regulator is required (max 64)");
        }
        String notice = doc.path("notice").asString("");
        if (!notice.toUpperCase(java.util.Locale.ROOT).contains("ILLUSTRATIVE")) {
            problems.add("notice must state that the layouts are ILLUSTRATIVE");
        }
        JsonNode calendar = doc.path("calendar");
        Map<String, JsonNode> tables = new LinkedHashMap<>();
        doc.path("gl_mapping_tables").properties().forEach(e -> {
            if (!e.getKey().matches("[A-Z][A-Z0-9_]{1,63}")) {
                problems.add("GL mapping table name " + e.getKey() + " must be an upper-case code");
            }
            if (!e.getValue().path("rules").isArray() || e.getValue().path("rules").isEmpty()) {
                problems.add("GL mapping table " + e.getKey() + " needs rules");
            }
            tables.put(e.getKey(), e.getValue());
        });
        JsonNode templates = doc.path("source_templates");
        List<Report> reports = new ArrayList<>();
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode r : doc.path("reports")) {
            String rc = r.path("code").asString("");
            if (rc.isBlank() || rc.length() > 64 || !codes.add(rc)) {
                problems.add("report code '" + rc + "' must be unique (max 64)");
                continue;
            }
            ObjectNode def = JsonNodeFactory.instance.objectNode();
            def.put("code", rc);
            def.put("name", r.path("name").asString(rc));
            def.put("country_pack", country);
            def.put("jurisdiction", country);
            def.put("regulator", r.path("regulator").asString(regulator));
            def.put("report_family", r.path("report_family").asString("OTHER"));
            def.put("frequency", r.path("frequency").asString(""));
            def.put("format", r.path("format").asString(""));
            if (r.hasNonNull("deadline_rule")) {
                def.put("deadline_rule", r.get("deadline_rule").asString());
            } else {
                def.putNull("deadline_rule");
            }
            def.put("business_day_convention", r.path("business_day_convention")
                    .asString(calendar.path("business_day_convention").asString("FOLLOWING")));
            def.set("holidays", calendar.has("holidays") ? calendar.get("holidays").deepCopy() : JsonNodeFactory.instance.arrayNode());
            def.put("description", r.path("description").asString(""));
            def.put("filing_offset_days", r.path("filing_offset_days").asInt(0));
            def.put("owner", r.path("owner").asString("FINANCE"));
            JsonNode v = r.path("version");
            if (!v.isObject()) {
                problems.add(rc + ": version is required");
                continue;
            }
            ObjectNode version1 = ((ObjectNode) v).deepCopy();
            ArrayNode sources = JsonNodeFactory.instance.arrayNode();
            for (JsonNode ds : v.path("data_sources")) {
                sources.add(expandSource(rc, ds, templates, tables, problems));
            }
            version1.set("data_sources", sources);
            if (!version1.has("validation_rules")) {
                version1.set("validation_rules", JsonNodeFactory.instance.arrayNode());
            }
            ObjectNode managed = JsonNodeFactory.instance.objectNode();
            MANAGED.forEach(f -> managed.set(f, def.get(f)));
            ObjectNode content = JsonNodeFactory.instance.objectNode();
            VERSION_CONTENT.forEach(f -> content.set(f, version1.get(f)));
            reports.add(new Report(rc, def, version1, r.get("sample"),
                    ReportRenderer.sha256(managed.toString()), ReportRenderer.sha256(content.toString())));
        }
        if (reports.isEmpty()) {
            problems.add("a pack needs at least one report");
        }
        if (!problems.isEmpty()) {
            throw new InvalidPack(code + "@" + version, problems);
        }
        return new RegulatoryPack(code, version, country, regulator, doc.path("currency").asString(null),
                doc.path("title").asString(code), notice, calendar, Map.copyOf(tables), List.copyOf(reports), doc);
    }

    private static JsonNode expandSource(String report, JsonNode ds, JsonNode templates, Map<String, JsonNode> tables,
                                         List<String> problems) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (ds.hasNonNull("use")) {
            JsonNode t = templates.get(ds.get("use").asString());
            if (t == null || !t.isObject()) {
                problems.add(report + ": unknown source template " + ds.get("use").asString());
            } else {
                out.setAll(((ObjectNode) t).deepCopy());
            }
        }
        ((ObjectNode) ds).properties().forEach(e -> {
            if (!"use".equals(e.getKey())) {
                out.set(e.getKey(), e.getValue().deepCopy());
            }
        });
        JsonNode gl = out.get("gl_mapping");
        if (gl != null && gl.isString()) {
            JsonNode table = tables.get(gl.asString());
            if (table == null) {
                problems.add(report + ": unknown GL mapping table " + gl.asString());
            } else {
                ObjectNode m = JsonNodeFactory.instance.objectNode();
                m.put("table", gl.asString());
                m.put("account_field", table.path("account_field").asString("gl_account_code"));
                m.put("class_field", table.path("class_field").asString("account_class"));
                m.set("rules", table.get("rules").deepCopy());
                out.set("gl_mapping", m);
            }
        }
        return out;
    }

    /** Catalog view: code, version, market, notice, reports and GL mapping tables (without the full mappings). */
    public ObjectNode summary() {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("pack_code", code);
        o.put("version", version);
        o.put("country", country);
        o.put("regulator", regulator);
        o.put("currency", currency);
        o.put("title", title);
        o.put("notice", notice);
        o.set("calendar", calendar.deepCopy());
        ArrayNode rs = o.putArray("reports");
        for (Report r : reports) {
            ObjectNode x = rs.addObject();
            x.put("code", r.code());
            for (String f : List.of("name", "report_family", "frequency", "format", "deadline_rule", "filing_offset_days",
                    "business_day_convention")) {
                x.set(f, r.definition().get(f));
            }
            x.set("effective_from", r.version().get("effective_from"));
            x.put("cell_count", r.version().path("field_mappings").size());
            x.put("rule_count", r.version().path("validation_rules").size());
            ArrayNode sources = x.putArray("data_sources");
            r.version().path("data_sources").forEach(ds -> {
                ObjectNode s = sources.addObject();
                s.set("name", ds.get("name"));
                s.put("type", ds.path("type").asString("SERVICE"));
                if (ds.hasNonNull("service")) {
                    s.set("service", ds.get("service"));
                    s.set("path", ds.get("path"));
                }
                if (ds.has("gl_mapping")) {
                    s.set("gl_mapping_table", ds.path("gl_mapping").get("table"));
                }
            });
        }
        ObjectNode t = o.putObject("gl_mapping_tables");
        glTables.forEach((name, table) -> {
            ObjectNode x = t.putObject(name);
            x.set("description", table.get("description"));
            x.put("rule_count", table.path("rules").size());
            x.put("config_key", "gl_mapping." + name.toLowerCase(java.util.Locale.ROOT));
            x.set("rules", table.get("rules").deepCopy());
        });
        return o;
    }
}
