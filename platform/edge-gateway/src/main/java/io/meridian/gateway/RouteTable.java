package io.meridian.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Method + path template routes generated from the spec (generated/routes.json). Lookup is by the first two
 * path segments, then regex; literal segments outrank path variables (e.g. /v1/holds/batch before /v1/holds/{id}).
 */
@Component
public class RouteTable {

    public record Route(String method, String template, Pattern pattern, String service, int port, int literals) {}

    private final Map<String, List<Route>> byPrefix = new HashMap<>();
    private final int size;

    public RouteTable(JsonMapper json) throws IOException {
        int n = 0;
        try (InputStream in = new ClassPathResource("meridian/routes.json").getInputStream()) {
            for (JsonNode r : json.readTree(in)) {
                String template = r.get("path").asString();
                String regex = "^" + template.replaceAll("\\{[^}/]+}", "[^/]+") + "$";
                int literals = (int) java.util.Arrays.stream(template.split("/")).filter(s -> !s.startsWith("{")).count();
                Route route = new Route(r.get("method").asString(), template, Pattern.compile(regex),
                        r.get("service").asString(), r.get("port").asInt(), literals);
                byPrefix.computeIfAbsent(prefix(template), k -> new ArrayList<>()).add(route);
                n++;
            }
        }
        byPrefix.values().forEach(l -> l.sort(Comparator.comparingInt(Route::literals).reversed()));
        size = n;
    }

    public int size() {
        return size;
    }

    public Optional<Route> match(String method, String path) {
        List<Route> candidates = byPrefix.getOrDefault(prefix(path), List.of());
        for (Route r : candidates) {
            if (r.method().equals(method) && r.pattern().matcher(path).matches()) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** True if some route matches the path with another HTTP method (405 instead of 404). */
    public boolean pathKnown(String path) {
        return byPrefix.getOrDefault(prefix(path), List.of()).stream().anyMatch(r -> r.pattern().matcher(path).matches());
    }

    private static String prefix(String path) {
        String[] parts = path.split("/", 4);
        return parts.length >= 3 ? parts[1] + "/" + parts[2] : path;
    }
}
