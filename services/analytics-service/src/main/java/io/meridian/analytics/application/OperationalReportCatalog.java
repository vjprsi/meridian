package io.meridian.analytics.application;

import io.meridian.analytics.domain.DatasetCatalog;
import io.meridian.analytics.domain.QueryDsl;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The operational / MIS reporting packs shipped with the service ({@code classpath:operational-reports/*.yaml}).
 * This is the governed OPERATIONAL report catalogue (transactions, settlement, cash-in/out, dormancy,
 * safeguarding) that complements the regulatory reporting engine. Each pack's saved queries are parsed and
 * validated against the whitelisted {@link DatasetCatalog} and the {@link QueryDsl} at start-up: an invalid query
 * stops the service rather than shipping a broken operational report. The pack is governance-as-code; instances are
 * created in a tenant through the ordinary {@code POST /v1/analytics/saved-queries} and
 * {@code POST /v1/analytics/dashboards} endpoints.
 */
@Component
public class OperationalReportCatalog {
    public static final String LOCATION = "classpath*:operational-reports/*.yaml";

    /** One shipped saved query: {@code ref} is the catalogue key dashboards reference. */
    public record SavedQuery(String ref, String name, String description, String query, String dataset,
                             List<JsonNode> parameters, String visibility) {}

    /** One widget on a shipped dashboard, bound to a saved query by its {@code queryRef}. */
    public record Widget(String title, String widgetType, String queryRef, JsonNode position) {}

    /** One shipped dashboard. */
    public record Dashboard(String name, String description, List<String> tags, Integer refreshSeconds,
                            List<Widget> widgets) {}

    private final List<SavedQuery> savedQueries;
    private final List<Dashboard> dashboards;

    @Autowired
    public OperationalReportCatalog(JsonMapper json) {
        this(load(json, LOCATION));
    }

    OperationalReportCatalog(List<JsonNode> packs) {
        List<SavedQuery> queries = new ArrayList<>();
        List<Dashboard> boards = new ArrayList<>();
        Set<String> refs = new LinkedHashSet<>();
        for (JsonNode pack : packs) {
            String packCode = pack.path("code").asString("operational-report");
            for (JsonNode q : array(pack, "saved_queries")) {
                SavedQuery sq = parseQuery(packCode, q);
                if (!refs.add(sq.ref())) {
                    throw new IllegalStateException("operational report ref " + sq.ref() + " is shipped twice");
                }
                queries.add(sq);
            }
            for (JsonNode d : array(pack, "dashboards")) {
                boards.add(parseDashboard(packCode, d, refs));
            }
        }
        this.savedQueries = List.copyOf(queries);
        this.dashboards = List.copyOf(boards);
    }

    private static SavedQuery parseQuery(String pack, JsonNode q) {
        String ref = text(q, "ref");
        String name = text(q, "name");
        String query = text(q, "query");
        if (ref == null || name == null || query == null) {
            throw new IllegalStateException("operational report in pack " + pack + " needs ref, name and query");
        }
        QueryDsl.Query parsed;
        try {
            parsed = QueryDsl.parse(query, dataset -> DatasetCatalog.find(dataset).map(DatasetCatalog.Dataset::fields));
        } catch (QueryDsl.QueryException e) {
            throw new IllegalStateException("operational report " + ref + " has an invalid query: " + e.getMessage(), e);
        }
        Set<String> declared = new LinkedHashSet<>();
        List<JsonNode> params = new ArrayList<>();
        for (JsonNode p : array(q, "parameters")) {
            String pn = text(p, "name");
            if (pn == null) {
                throw new IllegalStateException("operational report " + ref + " has a parameter without a name");
            }
            declared.add(pn);
            params.add(p);
        }
        for (String used : parsed.parameters()) {
            if (!declared.contains(used)) {
                throw new IllegalStateException("operational report " + ref + " uses :" + used + " but does not declare it");
            }
        }
        return new SavedQuery(ref, name, text(q, "description"), query, parsed.dataset(), List.copyOf(params),
                q.path("visibility").asString("SYSTEM"));
    }

    private static Dashboard parseDashboard(String pack, JsonNode d, Set<String> refs) {
        String name = text(d, "name");
        if (name == null) {
            throw new IllegalStateException("operational dashboard in pack " + pack + " needs a name");
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode t : array(d, "tags")) {
            tags.add(t.asString());
        }
        List<Widget> widgets = new ArrayList<>();
        for (JsonNode w : array(d, "widgets")) {
            String queryRef = text(w, "query_ref");
            if (queryRef != null && !refs.contains(queryRef)) {
                throw new IllegalStateException("dashboard '" + name + "' widget references unknown query ref " + queryRef);
            }
            widgets.add(new Widget(text(w, "title"), w.path("widget_type").asString("TABLE"), queryRef,
                    w.has("position") ? w.get("position") : null));
        }
        Integer refresh = d.hasNonNull("refresh_seconds") ? d.get("refresh_seconds").asInt() : null;
        return new Dashboard(name, text(d, "description"), List.copyOf(tags), refresh, List.copyOf(widgets));
    }

    public List<SavedQuery> savedQueries() {
        return savedQueries;
    }

    public List<Dashboard> dashboards() {
        return dashboards;
    }

    public Optional<SavedQuery> query(String ref) {
        return savedQueries.stream().filter(q -> q.ref().equals(ref)).findFirst();
    }

    private static List<JsonNode> array(JsonNode node, String field) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode a = node == null ? null : node.get(field);
        if (a != null && a.isArray()) {
            a.forEach(out::add);
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    /** Loads and parses every pack at {@code location} (also used by tests and tooling). */
    public static List<JsonNode> load(JsonMapper json, String location) {
        List<JsonNode> out = new ArrayList<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources(location)) {
                try (InputStream in = r.getInputStream()) {
                    out.add(parse(json, in));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read operational reports at " + location, e);
        }
        out.sort(Comparator.comparing(p -> p.path("code").asString("")));
        return out;
    }

    public static JsonNode parse(JsonMapper json, InputStream in) {
        LoaderOptions opts = new LoaderOptions();
        opts.setCodePointLimit(16 * 1024 * 1024);
        opts.setMaxAliasesForCollections(1000);
        Object doc = normalise(new Yaml(new SafeConstructor(opts)).load(in));
        return json.valueToTree(doc);
    }

    /** YAML timestamps / dates become ISO strings; everything else is kept (mirrors the regulatory pack loader). */
    static Object normalise(Object o) {
        if (o instanceof java.util.Date d) {
            return java.time.LocalDate.ofInstant(d.toInstant(), java.time.ZoneOffset.UTC).toString();
        }
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), normalise(v)));
            return out;
        }
        if (o instanceof List<?> l) {
            return l.stream().map(OperationalReportCatalog::normalise).toList();
        }
        return o;
    }
}
