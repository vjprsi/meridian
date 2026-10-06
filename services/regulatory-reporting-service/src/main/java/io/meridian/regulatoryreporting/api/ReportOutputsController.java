package io.meridian.regulatoryreporting.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.regulatoryreporting.api.generated.ReportOutputsApi;
import io.meridian.regulatoryreporting.infrastructure.Store;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Report outputs (append-only files); content served per format with its checksum as ETag. */
@RestController
public class ReportOutputsController implements ReportOutputsApi {
    private final Store store;
    private final Responses responses;

    public ReportOutputsController(Store store, Responses responses) {
        this.store = store;
        this.responses = responses;
    }

    @Override
    public ResponseEntity<String> reportOutputsXml(UUID id) {
        return content(id, Set.of("XML", "XBRL"));
    }

    @Override
    public ResponseEntity<String> reportOutputsCsv(UUID id) {
        return content(id, Set.of("CSV"));
    }

    @Override
    public ResponseEntity<String> reportOutputsJson(UUID id) {
        return content(id, Set.of("JSON"));
    }

    private ResponseEntity<String> content(UUID id, Set<String> formats) {
        TenantContext.require();
        JsonNode o = store.require("report_output", id);
        String format = o.get("format").asString();
        if (!formats.contains(format)) {
            throw new ApiException(404, "format_not_available", "Output " + id + " is a " + format
                    + " file; use the /" + format.toLowerCase(java.util.Locale.ROOT).replace("xbrl", "xml") + " endpoint");
        }
        ResponseEntity<String> r = responses.file(o.get("content").asString(), o.get("content_type").asString(),
                o.get("file_name").asString());
        return ResponseEntity.status(r.getStatusCode()).headers(r.getHeaders())
                .header("X-Content-SHA256", o.get("sha256").asString()).body(r.getBody());
    }
}
