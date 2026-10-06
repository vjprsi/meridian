package io.meridian.productcatalog.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.productcatalog.api.generated.ProductVersionsApi;
import io.meridian.productcatalog.api.generated.model.EligibilityRulePage;
import io.meridian.productcatalog.api.generated.model.FeeDefinitionPage;
import io.meridian.productcatalog.api.generated.model.InterestRateConfigPage;
import io.meridian.productcatalog.api.generated.model.ProductLimitTemplatePage;
import io.meridian.productcatalog.api.generated.model.ProductParameterPage;
import io.meridian.productcatalog.api.generated.model.ProductVersion;
import io.meridian.productcatalog.api.generated.model.ProductVersionCreate;
import io.meridian.productcatalog.api.generated.model.ProductVersionUpdate;
import io.meridian.productcatalog.api.generated.model.ProductVersionsApproveRequest;
import io.meridian.productcatalog.api.generated.model.ProductVersionsMigrateAccountsRequest;
import io.meridian.productcatalog.api.generated.model.ProductVersionsPublishRequest;
import io.meridian.productcatalog.api.generated.model.ProductVersionsRejectRequest;
import io.meridian.productcatalog.api.generated.model.ProductVersionsSubmitRequest;
import io.meridian.productcatalog.application.VersionService;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Product versions: drafting, maker-checker lifecycle, publication, new drafts, diffs, migrations, content views. */
@RestController
public class ProductVersionsController implements ProductVersionsApi {
    private final VersionService versions;
    private final CatalogStore store;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public ProductVersionsController(VersionService versions, CatalogStore store, CrudEngine crud, Idempotency idempotency,
                                     JsonMapper json) {
        this.versions = versions;
        this.store = store;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<ProductVersion> productVersionsCreate(String idempotencyKey, ProductVersionCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, ProductVersion.class,
                () -> respond(HttpStatus.CREATED, versions.create(body)));
    }

    @Override
    public ResponseEntity<ProductVersion> productVersionsUpdate(UUID id, String ifMatch, ProductVersionUpdate body) {
        TenantContext.require();
        return respond(HttpStatus.OK, versions.update(id, body, ApiSupport.ifMatch(ifMatch)));
    }

    @Override
    public ResponseEntity<ProductVersion> productVersionsSubmit(UUID id, String idempotencyKey, ProductVersionsSubmitRequest body) {
        JsonNode req = tree(body);
        return action("submit", id, idempotencyKey, body, () -> versions.submit(id, ApiSupport.text(req, "comment")));
    }

    @Override
    public ResponseEntity<ProductVersion> productVersionsApprove(UUID id, String idempotencyKey, ProductVersionsApproveRequest body) {
        JsonNode req = tree(body);
        return action("approve", id, idempotencyKey, body, () -> versions.approve(id, ApiSupport.text(req, "comment")));
    }

    @Override
    public ResponseEntity<ProductVersion> productVersionsReject(UUID id, String idempotencyKey, ProductVersionsRejectRequest body) {
        JsonNode req = tree(body);
        return action("reject", id, idempotencyKey, body, () -> versions.reject(id, ApiSupport.text(req, "reason")));
    }

    @Override
    public ResponseEntity<ProductVersion> productVersionsPublish(UUID id, String idempotencyKey, ProductVersionsPublishRequest body) {
        JsonNode req = tree(body);
        OffsetDateTime from = req.hasNonNull("effective_from") ? OffsetDateTime.parse(req.get("effective_from").asString()) : null;
        return action("publish", id, idempotencyKey, body, () -> versions.publish(id, from));
    }

    @Override
    public ResponseEntity<ProductVersion> productVersionsNewDraft(UUID id, String idempotencyKey) {
        return action("new-draft", id, idempotencyKey, null, () -> versions.newDraft(id));
    }

    @Override
    public ResponseEntity<ProductVersion> productVersionsMigrateAccounts(UUID id, String idempotencyKey,
                                                                         ProductVersionsMigrateAccountsRequest body) {
        JsonNode req = tree(body);
        UUID from = req.hasNonNull("from_version_id") ? UUID.fromString(req.get("from_version_id").asString()) : null;
        LocalDate date = req.hasNonNull("effective_date") ? LocalDate.parse(req.get("effective_date").asString()) : null;
        TenantContext.require();
        return idempotency.execute(idempotencyKey, ApiSupport.idem(json, "migrate-accounts", id, body), ProductVersion.class,
                () -> respond(HttpStatus.OK, versions.migrateAccounts(id, from, date)));
    }

    /** {@code GET /v1/product-versions/{id}/diff?against=<version id>}: changes from {@code against} to this one. */
    @Override
    public ResponseEntity<Void> productVersionsDiff(UUID id) {
        TenantContext.require();
        String against = ApiSupport.param("against");
        UUID other;
        try {
            other = against == null ? null : UUID.fromString(against);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_against", "against must be a version id");
        }
        return ApiSupport.document(MediaType.APPLICATION_JSON, store.toNode(versions.diff(id, other)).toString());
    }

    @Override
    public ResponseEntity<ProductParameterPage> productVersionsParameters(UUID id, Integer limit, String cursor) {
        return children("product_parameter", ProductParameterPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<InterestRateConfigPage> productVersionsInterestRates(UUID id, Integer limit, String cursor) {
        return children("interest_rate_config", InterestRateConfigPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<FeeDefinitionPage> productVersionsFees(UUID id, Integer limit, String cursor) {
        return children("fee_definition", FeeDefinitionPage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<ProductLimitTemplatePage> productVersionsLimits(UUID id, Integer limit, String cursor) {
        return children("product_limit_template", ProductLimitTemplatePage.class, id, limit, cursor);
    }

    @Override
    public ResponseEntity<EligibilityRulePage> productVersionsEligibilityRules(UUID id, Integer limit, String cursor) {
        return children("eligibility_rule", EligibilityRulePage.class, id, limit, cursor);
    }

    private <T> ResponseEntity<T> children(String table, Class<T> type, UUID versionId, Integer limit, String cursor) {
        TenantContext.require();
        store.require("product_version", versionId);
        return crud.list(table, type, Crud.params().with("product_version_id", versionId).with("limit", limit)
                .with("cursor", cursor));
    }

    private ResponseEntity<ProductVersion> action(String op, UUID id, String key, Object body, Supplier<String> run) {
        TenantContext.require();
        return idempotency.execute(key, ApiSupport.idem(json, op, id, body), ProductVersion.class,
                () -> respond(HttpStatus.OK, run.get()));
    }

    private ResponseEntity<ProductVersion> respond(HttpStatus status, String row) {
        return ApiSupport.withEtag(status, crud.read(row, ProductVersion.class), json.readTree(row));
    }

    private JsonNode tree(Object body) {
        return body == null ? json.createObjectNode() : json.valueToTree(body);
    }
}
