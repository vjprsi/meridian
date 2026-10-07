package io.meridian.regulatoryreporting.application;

import io.meridian.common.error.ApiException;
import io.meridian.regulatoryreporting.domain.RegulatoryPack;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The regulatory packs shipped with the service ({@code classpath:regulatory-packs/*.yaml}, one file per pack; the
 * file carries the pack's current version). Loaded and validated at start-up: an invalid pack stops the service
 * rather than installing half a pack later.
 */
@Component
public class RegulatoryPackCatalog {
    public static final String LOCATION = "classpath*:regulatory-packs/*.yaml";

    private final Map<String, RegulatoryPack> packs;

    @org.springframework.beans.factory.annotation.Autowired
    public RegulatoryPackCatalog(JsonMapper json) {
        this(load(json, LOCATION));
    }

    RegulatoryPackCatalog(List<RegulatoryPack> list) {
        Map<String, RegulatoryPack> m = new LinkedHashMap<>();
        list.stream().sorted(Comparator.comparing(RegulatoryPack::code)).forEach(p -> {
            if (m.put(p.code(), p) != null) {
                throw new IllegalStateException("regulatory pack " + p.code() + " is shipped twice");
            }
        });
        this.packs = Map.copyOf(m);
    }

    public List<RegulatoryPack> all() {
        return packs.values().stream().sorted(Comparator.comparing(RegulatoryPack::code)).toList();
    }

    public RegulatoryPack require(String code) {
        RegulatoryPack p = code == null ? null : packs.get(code);
        if (p == null) {
            throw ApiException.unprocessable("unknown_pack", "No regulatory pack " + code + " is shipped; available: "
                    + all().stream().map(RegulatoryPack::code).toList());
        }
        return p;
    }

    /** Loads and parses every pack at {@code location} (also used by tests and tooling). */
    public static List<RegulatoryPack> load(JsonMapper json, String location) {
        List<RegulatoryPack> out = new ArrayList<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources(location)) {
                try (InputStream in = r.getInputStream()) {
                    out.add(parse(json, in));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read regulatory packs at " + location, e);
        }
        return out;
    }

    public static RegulatoryPack parse(JsonMapper json, InputStream in) {
        LoaderOptions opts = new LoaderOptions();
        opts.setCodePointLimit(16 * 1024 * 1024);
        opts.setMaxAliasesForCollections(1000);
        Object doc = normalise(new Yaml(new SafeConstructor(opts)).load(in));
        return RegulatoryPack.parse(json.valueToTree(doc));
    }

    /** YAML timestamps (unquoted dates) become ISO date strings; everything else is kept. */
    static Object normalise(Object o) {
        if (o instanceof Date d) {
            return LocalDate.ofInstant(d.toInstant(), ZoneOffset.UTC).toString();
        }
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), normalise(v)));
            return out;
        }
        if (o instanceof List<?> l) {
            return l.stream().map(RegulatoryPackCatalog::normalise).toList();
        }
        return o;
    }
}
