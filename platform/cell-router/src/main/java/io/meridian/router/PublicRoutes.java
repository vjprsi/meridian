package io.meridian.router;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The public (unauthenticated provider callback) operations of the contract, from generated/routes.json: only these may
 * be routed by their {@code tenant_id} parameter instead of a verified token.
 */
public class PublicRoutes {
    private record Route(String method, Pattern pattern) {}

    private final List<Route> routes = new ArrayList<>();

    public PublicRoutes(JsonMapper json) throws IOException {
        ClassPathResource res = new ClassPathResource("meridian/routes.json");
        if (!res.exists()) {
            return;
        }
        try (InputStream in = res.getInputStream()) {
            for (JsonNode r : json.readTree(in)) {
                if (r.path("public").asBoolean(false)) {
                    String template = r.get("path").asString();
                    routes.add(new Route(r.get("method").asString(),
                            Pattern.compile("^" + template.replaceAll("\\{[^}/]+}", "[^/]+") + "$")));
                }
            }
        }
    }

    public boolean isPublic(String method, String path) {
        for (Route r : routes) {
            if (r.method().equals(method) && r.pattern().matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return routes.size();
    }
}
