package io.meridian.regulatoryreporting.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.regulatoryreporting.api.generated.RegulatoryPackInstallationsApi;
import io.meridian.regulatoryreporting.api.generated.model.RegulatoryPackInstallationsInstallRequest;
import io.meridian.regulatoryreporting.application.Fields;
import io.meridian.regulatoryreporting.application.RegulatoryPackInstaller;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Regulatory packs: the shipped catalog and the idempotent install / upgrade action. List / get are generic CRUD. */
@RestController
public class RegulatoryPackInstallationsController implements RegulatoryPackInstallationsApi {
    private final RegulatoryPackInstaller installer;
    private final Idempotency idempotency;
    private final Responses responses;
    private final JsonMapper json;

    public RegulatoryPackInstallationsController(RegulatoryPackInstaller installer, Idempotency idempotency,
                                                 Responses responses, JsonMapper json) {
        this.installer = installer;
        this.idempotency = idempotency;
        this.responses = responses;
        this.json = json;
    }

    @Override
    public ResponseEntity<Map<String, Object>> regulatoryPackInstallationsCatalog() {
        TenantContext.require();
        return responses.object(installer.catalog());
    }

    @Override
    public ResponseEntity<Map<String, Object>> regulatoryPackInstallationsInstall(String idempotencyKey,
                                                                                RegulatoryPackInstallationsInstallRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String pack = Fields.requireText(req, "pack_code");
        ObjectNode fingerprint = json.createObjectNode().put("action", "install-regulatory-pack");
        fingerprint.set("body", req);
        return responses.object(idempotency.execute(idempotencyKey, fingerprint, JsonNode.class,
                () -> ResponseEntity.ok(installer.install(pack, Fields.text(req, "version"), Fields.uuid(req, "legal_entity_id"),
                        Fields.bool(req, "dry_run", false), Fields.bool(req, "enabled", true)))));
    }
}
