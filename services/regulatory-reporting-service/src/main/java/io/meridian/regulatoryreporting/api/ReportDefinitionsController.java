package io.meridian.regulatoryreporting.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.regulatoryreporting.api.generated.ReportDefinitionsApi;
import io.meridian.regulatoryreporting.api.generated.model.ReportDefinition;
import io.meridian.regulatoryreporting.api.generated.model.ReportDefinitionCreate;
import io.meridian.regulatoryreporting.api.generated.model.ReportDefinitionUpdate;
import io.meridian.regulatoryreporting.api.generated.model.ReportDefinitionVersion;
import io.meridian.regulatoryreporting.api.generated.model.ReportDefinitionVersionPage;
import io.meridian.regulatoryreporting.api.generated.model.ReportDefinitionsDeprecateRequest;
import io.meridian.regulatoryreporting.api.generated.model.ReportDefinitionsPublishRequest;
import io.meridian.regulatoryreporting.api.generated.model.ReportDefinitionsAddVersionsRequest;
import io.meridian.regulatoryreporting.application.DefinitionService;
import io.meridian.regulatoryreporting.application.Fields;
import io.meridian.regulatoryreporting.infrastructure.Store;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Report definitions: catalogue CRUD with format / deadline checks, versions (maker), publish (checker), calendar. */
@RestController
public class ReportDefinitionsController implements ReportDefinitionsApi {
    private final DefinitionService definitions;
    private final Idempotency idempotency;
    private final Responses responses;
    private final CrudEngine crud;
    private final Store store;
    private final JsonMapper json;

    public ReportDefinitionsController(DefinitionService definitions, Idempotency idempotency, Responses responses,
                                       CrudEngine crud, Store store, JsonMapper json) {
        this.definitions = definitions;
        this.idempotency = idempotency;
        this.responses = responses;
        this.crud = crud;
        this.store = store;
        this.json = json;
    }

    @Override
    public ResponseEntity<ReportDefinition> reportDefinitionsCreate(String idempotencyKey, ReportDefinitionCreate body) {
        TenantContext.require();
        definitions.checkDefinition(json.valueToTree(body), null);
        return crud.create("report_definition", ReportDefinition.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<ReportDefinition> reportDefinitionsUpdate(UUID id, String ifMatch, ReportDefinitionUpdate body) {
        TenantContext.require();
        JsonNode existing = store.require("report_definition", id);
        definitions.checkDefinition(json.valueToTree(body), existing.get("frequency").asString());
        return crud.update("report_definition", ReportDefinition.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<ReportDefinitionVersionPage> reportDefinitionsVersions(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        store.require("report_definition", id);
        return crud.list("report_definition_version", ReportDefinitionVersionPage.class,
                Crud.params().with("report_definition_id", id).with("limit", limit).with("cursor", cursor));
    }

    @Override
    public ResponseEntity<ReportDefinitionVersion> reportDefinitionsAddVersions(UUID id, String idempotencyKey,
                                                                               ReportDefinitionsAddVersionsRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, responses.action("add-version", id, req), ReportDefinitionVersion.class,
                () -> responses.created(definitions.addVersion(id, req), ReportDefinitionVersion.class));
    }

    @Override
    public ResponseEntity<ReportDefinitionVersion> reportDefinitionsPublish(UUID id, String idempotencyKey,
                                                                           ReportDefinitionsPublishRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        Integer version = Fields.integer(req, "definition_version");
        if (version == null) {
            throw Fields.invalid("definition_version", "required", "definition_version is required");
        }
        return idempotency.execute(idempotencyKey, responses.action("publish", id, req), ReportDefinitionVersion.class,
                () -> responses.ok(definitions.publish(id, version, Fields.text(req, "comment")), ReportDefinitionVersion.class));
    }

    @Override
    public ResponseEntity<ReportDefinition> reportDefinitionsDeprecate(UUID id, String idempotencyKey,
                                                                      ReportDefinitionsDeprecateRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Fields.requireText(req, "reason");
        return idempotency.execute(idempotencyKey, responses.action("deprecate", id, req), ReportDefinition.class,
                () -> responses.ok(definitions.deprecate(id, reason), ReportDefinition.class));
    }

    @Override
    public ResponseEntity<Map<String, Object>> reportDefinitionsCalendar(UUID id, LocalDate from, LocalDate to,
                                                                        UUID legalEntityId) {
        TenantContext.require();
        if (store.find("report_definition", id).isEmpty()) {
            throw ApiException.notFound("report definition", id);
        }
        return responses.object(definitions.calendar(id, from, to, legalEntityId));
    }
}
