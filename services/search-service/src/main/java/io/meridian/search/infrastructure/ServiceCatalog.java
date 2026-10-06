package io.meridian.search.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Names of all Meridian services (from the generated services manifest): valid job targets. */
@Component
public class ServiceCatalog {
    private final Set<String> names = new TreeSet<>();

    public ServiceCatalog(JsonMapper json) {
        ClassPathResource manifest = new ClassPathResource("meridian/services.json");
        if (manifest.exists()) {
            try (InputStream in = manifest.getInputStream()) {
                json.readTree(in).forEach(s -> names.add(s.get("name").asString()));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read services manifest", e);
            }
        }
    }

    /** True if the service is known, or when no manifest is packaged (nothing to check against). */
    public boolean exists(String service) {
        return names.isEmpty() || names.contains(service);
    }
}
