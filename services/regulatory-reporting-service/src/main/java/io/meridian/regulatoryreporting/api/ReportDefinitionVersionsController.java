package io.meridian.regulatoryreporting.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.regulatoryreporting.api.generated.ReportDefinitionVersionsApi;
import io.meridian.regulatoryreporting.api.generated.model.ReportDefinitionVersion;
import io.meridian.regulatoryreporting.api.generated.model.ReportDefinitionVersionsRejectRequest;
import io.meridian.regulatoryreporting.application.DefinitionService;
import io.meridian.regulatoryreporting.application.Fields;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Report definition versions: list/get (generated CRUD) and the checker's rejection of a DRAFT version. */
@RestController
public class ReportDefinitionVersionsController implements ReportDefinitionVersionsApi {
    private final DefinitionService definitions;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public ReportDefinitionVersionsController(DefinitionService definitions, Idempotency idempotency, Responses responses,
                                              JsonMapper json) {
        this.definitions = definitions;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<ReportDefinitionVersion> reportDefinitionVersionsReject(UUID id, String idempotencyKey,
                                                                                  ReportDefinitionVersionsRejectRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Fields.requireText(req, "reason");
        return idempotency.execute(idempotencyKey, responses.action("reject", id, req), ReportDefinitionVersion.class,
                () -> responses.ok(definitions.rejectVersion(id, reason), ReportDefinitionVersion.class));
    }
}
