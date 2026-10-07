package io.meridian.mover;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where the services and the object store of a cell are. Per cell code from {@code meridian.mover.cells.<code>}
 * (credentials live only there), else from the cell row in the copy request ({@code endpoints.services_url_template},
 * {@code endpoints.s3_endpoint}, {@code endpoints.s3_bucket}, {@code endpoints.s3_region}). The URL template takes
 * {@code {name}} and {@code {port}} (e.g. {@code http://{name}.meridian.svc.cluster.local:{port}} through the cells'
 * private connectivity, or {@code http://localhost:{port}} locally).
 */
public class Cells {
    /** A data-owning service from the generated manifest. */
    public record Service(String name, int port, String database) {}

    public record Cell(String code, String servicesUrlTemplate, MoverProperties.S3 s3) {
        public String url(Service s, String path) {
            return servicesUrlTemplate.replace("{name}", s.name()).replace("{port}", Integer.toString(s.port())) + path;
        }
    }

    private final MoverProperties props;
    private final List<Service> services = new ArrayList<>();

    public Cells(MoverProperties props, JsonMapper json) {
        this.props = props;
        ClassPathResource manifest = new ClassPathResource("meridian/services.json");
        Map<String, Service> all = new LinkedHashMap<>();
        if (manifest.exists()) {
            try (InputStream in = manifest.getInputStream()) {
                for (JsonNode s : json.readTree(in)) {
                    String db = s.path("database").asString("");
                    if (!db.isBlank()) {
                        all.put(s.get("name").asString(), new Service(s.get("name").asString(), s.get("port").asInt(), db));
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot read services manifest", e);
            }
        }
        List<String> wanted = props.services().isEmpty() ? new ArrayList<>(all.keySet()) : props.services();
        for (String name : wanted) {
            Service s = all.get(name);
            if (s == null) {
                throw new IllegalStateException("meridian.mover.services names unknown service " + name);
            }
            if (!props.skipServices().contains(name)) {
                services.add(s);
            }
        }
    }

    /** Services whose tenant data is copied, in a stable order. */
    public List<Service> services() {
        return services;
    }

    /** The cell named in the request ({@code from_cell} / {@code to_cell} object). */
    public Cell resolve(JsonNode cellRef) {
        String code = cellRef.path("code").asString("");
        MoverProperties.Cell conf = props.cells().get(code);
        JsonNode ep = cellRef.path("endpoints");
        String template = conf != null && conf.servicesUrlTemplate() != null ? conf.servicesUrlTemplate()
                : ep.path("services_url_template").asString(null);
        if (template == null || template.isBlank()) {
            throw new MoverHttp.PermanentException("No services URL for cell " + code
                    + ": set meridian.mover.cells." + code + ".services-url-template or the cell's endpoints.services_url_template");
        }
        MoverProperties.S3 s3 = conf == null ? null : conf.s3();
        if (s3 == null && ep.hasNonNull("s3_endpoint")) {
            s3 = new MoverProperties.S3(ep.get("s3_endpoint").asString(), ep.path("s3_bucket").asString(null),
                    ep.path("s3_region").asString("us-east-1"), null, null);
        }
        return new Cell(code, template.endsWith("/") ? template.substring(0, template.length() - 1) : template, s3);
    }
}
