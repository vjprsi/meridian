package io.meridian.tenant.application;

import io.meridian.common.error.ApiException;
import io.meridian.tenant.domain.StarterPack;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The starter packs shipped with the service: every {@code starter-packs/*.yaml} on the classpath, one file per pack
 * version, so older versions stay available side by side. A pack may {@code extends} another pack code (its latest
 * version) and override parameters and items. Loaded and validated once at start-up: an invalid pack fails the boot.
 */
@Component
public class StarterPackCatalog {
    private final Map<String, TreeMap<String, StarterPack>> packs = new TreeMap<>();

    @Autowired
    public StarterPackCatalog(JsonMapper json,
                              @Value("${meridian.starter-packs.location:classpath*:starter-packs/*.yaml}") String location) {
        this(load(json, location));
    }

    StarterPackCatalog(List<StarterPack> all) {
        for (StarterPack p : all) {
            if (packs.computeIfAbsent(p.code(), c -> new TreeMap<>(StarterPackCatalog::compareVersions))
                    .putIfAbsent(p.version(), p) != null) {
                throw new IllegalStateException("starter pack " + p.code() + "@" + p.version() + " is defined twice");
            }
        }
    }

    /** Reads, merges ({@code extends}) and validates every pack document found at {@code location}. */
    public static List<StarterPack> load(JsonMapper json, String location) {
        Map<String, JsonNode> raw = new HashMap<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources(location)) {
                try (InputStream in = r.getInputStream()) {
                    JsonNode doc = read(json, in);
                    String id = doc.path("code").asString("") + "@" + doc.path("version").asString("");
                    if (raw.put(id, doc) != null) {
                        throw new IllegalStateException("starter pack " + id + " is defined twice (" + r.getDescription() + ")");
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read starter packs from " + location, e);
        }
        List<StarterPack> out = new ArrayList<>();
        for (JsonNode doc : raw.values()) {
            out.add(StarterPack.parse(resolve(doc, raw, 0)));
        }
        out.sort(Comparator.comparing(StarterPack::code).thenComparing(StarterPack::version, StarterPackCatalog::compareVersions));
        return out;
    }

    public static JsonNode read(JsonMapper json, InputStream in) {
        LoaderOptions opts = new LoaderOptions();
        opts.setMaxAliasesForCollections(50);
        Object doc = new Yaml(new SafeConstructor(opts)).load(in);
        JsonNode node = json.valueToTree(doc);
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("a starter pack file must hold one YAML mapping");
        }
        return node;
    }

    private static JsonNode resolve(JsonNode doc, Map<String, JsonNode> raw, int depth) {
        String parent = doc.path("extends").asString(null);
        if (parent == null) {
            return doc;
        }
        if (depth > 5) {
            throw new IllegalStateException("starter pack extends chain too deep at " + doc.path("code").asString());
        }
        // "CODE@VERSION" pins the base version; "CODE" follows its latest version
        JsonNode base = raw.entrySet().stream()
                .filter(e -> parent.contains("@") ? e.getKey().equals(parent) : e.getKey().startsWith(parent + "@"))
                .max((a, b) -> compareVersions(a.getValue().path("version").asString(), b.getValue().path("version").asString()))
                .map(Map.Entry::getValue)
                .orElseThrow(() -> new IllegalStateException(doc.path("code").asString() + " extends unknown pack " + parent));
        return StarterPack.merge(resolve(base, raw, depth + 1), doc);
    }

    public Optional<StarterPack> find(String code, String version) {
        if (code == null) {
            return Optional.empty();
        }
        TreeMap<String, StarterPack> versions = packs.get(code.toUpperCase(java.util.Locale.ROOT));
        if (versions == null || versions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(version == null || version.isBlank() ? versions.lastEntry().getValue() : versions.get(version));
    }

    /** The requested pack (latest version when none is given) or 422. */
    public StarterPack require(String code, String version) {
        return find(code, version).orElseThrow(() -> {
            TreeMap<String, StarterPack> versions = code == null ? null : packs.get(code.toUpperCase(java.util.Locale.ROOT));
            return versions == null
                    ? ApiException.unprocessable("unknown_starter_pack", "Unknown starter pack " + code + "; available: "
                    + String.join(", ", packs.keySet()))
                    : ApiException.unprocessable("unknown_starter_pack_version", "Starter pack " + code + " has versions "
                    + String.join(", ", versions.keySet()));
        });
    }

    public List<StarterPack> all() {
        List<StarterPack> out = new ArrayList<>();
        packs.values().forEach(v -> out.addAll(v.values()));
        return out;
    }

    /** Catalog summary: one entry per pack code with its versions, parameters and steps (latest version). */
    public ObjectNode summary(JsonMapper json) {
        ObjectNode out = json.createObjectNode();
        ArrayNode data = out.putArray("data");
        packs.forEach((code, versions) -> {
            StarterPack latest = versions.lastEntry().getValue();
            ObjectNode p = data.addObject();
            p.put("code", code);
            p.put("title", latest.title());
            p.put("market", latest.market());
            p.put("description", latest.description());
            p.put("notice", latest.definition().path("notice").asString(""));
            p.put("latest_version", latest.version());
            ArrayNode vs = p.putArray("versions");
            versions.keySet().forEach(vs::add);
            ObjectNode params = p.putObject("parameters");
            latest.parameters().forEach((k, v) -> {
                ObjectNode d = params.putObject(k);
                d.put("type", v.type());
                d.set("default", v.defaultValue());
                d.put("description", v.description());
                if (v.tenantFact() != null) {
                    d.put("tenant_fact", v.tenantFact());
                }
            });
            ArrayNode steps = p.putArray("steps");
            latest.steps().forEach(s -> {
                ObjectNode o = steps.addObject();
                o.put("code", s.code());
                o.put("title", s.title());
                int[] n = {0};
                s.items().forEach(i -> StarterPack.walk(i, x -> n[0]++));
                o.put("items", n[0]);
            });
        });
        return out;
    }

    /** Orders YYYY.M.N versions numerically. */
    public static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(i < x.length ? Integer.parseInt(x[i]) : 0, i < y.length ? Integer.parseInt(y[i]) : 0);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
