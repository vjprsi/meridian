package io.meridian.productcatalog.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.productcatalog.api.generated.ProductScriptsApi;
import io.meridian.productcatalog.api.generated.model.ProductScript;
import io.meridian.productcatalog.api.generated.model.ProductScriptCreate;
import io.meridian.productcatalog.api.generated.model.ProductScriptUpdate;
import io.meridian.productcatalog.api.generated.model.ProductScriptsApproveRequest;
import io.meridian.productcatalog.api.generated.model.ProductScriptsDeprecateRequest;
import io.meridian.productcatalog.api.generated.model.ProductScriptsSubmitRequest;
import io.meridian.productcatalog.api.generated.model.ProductScriptsTestRequest;
import io.meridian.productcatalog.application.ScriptService;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Product scripts: compile, test, maker-checker activation, deprecation. */
@RestController
public class ProductScriptsController implements ProductScriptsApi {
    private final ScriptService scripts;
    private final CatalogStore store;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public ProductScriptsController(ScriptService scripts, CatalogStore store, CrudEngine crud, Idempotency idempotency,
                                    JsonMapper json) {
        this.scripts = scripts;
        this.store = store;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<ProductScript> productScriptsCreate(String idempotencyKey, ProductScriptCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, ProductScript.class,
                () -> respond(HttpStatus.CREATED, scripts.create(body)));
    }

    @Override
    public ResponseEntity<ProductScript> productScriptsUpdate(UUID id, String ifMatch, ProductScriptUpdate body) {
        TenantContext.require();
        return respond(HttpStatus.OK, scripts.update(id, body, ApiSupport.ifMatch(ifMatch)));
    }

    @Override
    public ResponseEntity<ProductScript> productScriptsCompile(UUID id, String idempotencyKey) {
        return action("compile", id, idempotencyKey, null, () -> scripts.compile(id));
    }

    @Override
    public ResponseEntity<Void> productScriptsTest(UUID id, String idempotencyKey, ProductScriptsTestRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return ApiSupport.asVoid(idempotency.execute(idempotencyKey, ApiSupport.idem(json, "script-test", id, body),
                String.class, () -> ResponseEntity.ok(store.toNode(
                        scripts.test(id, req.hasNonNull("inputs") ? CatalogStore.toMap(req.get("inputs")) : null)).toString())));
    }

    @Override
    public ResponseEntity<ProductScript> productScriptsSubmit(UUID id, String idempotencyKey, ProductScriptsSubmitRequest body) {
        JsonNode req = tree(body);
        return action("submit", id, idempotencyKey, body, () -> scripts.submit(id, ApiSupport.text(req, "comment")));
    }

    @Override
    public ResponseEntity<ProductScript> productScriptsApprove(UUID id, String idempotencyKey, ProductScriptsApproveRequest body) {
        JsonNode req = tree(body);
        return action("approve", id, idempotencyKey, body, () -> scripts.approve(id, ApiSupport.text(req, "comment")));
    }

    @Override
    public ResponseEntity<ProductScript> productScriptsDeprecate(UUID id, String idempotencyKey,
                                                                 ProductScriptsDeprecateRequest body) {
        JsonNode req = tree(body);
        return action("deprecate", id, idempotencyKey, body, () -> scripts.deprecate(id, ApiSupport.text(req, "reason")));
    }

    private ResponseEntity<ProductScript> action(String op, UUID id, String key, Object body, Supplier<String> run) {
        TenantContext.require();
        return idempotency.execute(key, ApiSupport.idem(json, op, id, body), ProductScript.class,
                () -> respond(HttpStatus.OK, run.get()));
    }

    private ResponseEntity<ProductScript> respond(HttpStatus status, String row) {
        return ApiSupport.withEtag(status, crud.read(row, ProductScript.class), json.readTree(row));
    }

    private JsonNode tree(Object body) {
        return body == null ? json.createObjectNode() : json.valueToTree(body);
    }
}
