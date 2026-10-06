package io.meridian.filegateway.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.filegateway.api.generated.OutboundFilesApi;
import io.meridian.filegateway.api.generated.model.OutboundFile;
import io.meridian.filegateway.api.generated.model.OutboundFilesAcknowledgeRequest;
import io.meridian.filegateway.api.generated.model.OutboundFilesCancelRequest;
import io.meridian.filegateway.api.generated.model.OutboundFilesGenerateRequest;
import io.meridian.filegateway.api.generated.model.OutboundFilesRedeliverRequest;
import io.meridian.filegateway.application.OutboundService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbound files. {@code POST /generate} carries the file: {@code records} (rendered with
 * the format layout), {@code content} or {@code content_base64}.
 */
@RestController
public class OutboundFilesController implements OutboundFilesApi {
    private final OutboundService outbound;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public OutboundFilesController(OutboundService outbound, CrudEngine crud, Idempotency idempotency, JsonMapper json) {
        this.outbound = outbound;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<OutboundFile> outboundFilesGenerate(String idempotencyKey, OutboundFilesGenerateRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, req, OutboundFile.class, () -> ResponseEntity.ok(
                crud.read(outbound.create(crud.toColumns("outbound_file", body), req).toString(), OutboundFile.class)));
    }

    @Override
    public ResponseEntity<OutboundFile> outboundFilesRedeliver(UUID id, String idempotencyKey, OutboundFilesRedeliverRequest body) {
        TenantContext.require();
        JsonNode req = body == null ? json.createObjectNode() : json.valueToTree(body);
        return idempotency.execute(idempotencyKey, req, OutboundFile.class,
                () -> ResponseEntity.ok(crud.read(outbound.redeliver(id, Http.text(req, "reason")).toString(), OutboundFile.class)));
    }

    @Override
    public ResponseEntity<OutboundFile> outboundFilesCancel(UUID id, String idempotencyKey, OutboundFilesCancelRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Http.required(req, "reason");
        return idempotency.execute(idempotencyKey, req, OutboundFile.class,
                () -> ResponseEntity.ok(crud.read(outbound.cancel(id, reason).toString(), OutboundFile.class)));
    }

    @Override
    public ResponseEntity<OutboundFile> outboundFilesAcknowledge(UUID id, String idempotencyKey,
                                                                 OutboundFilesAcknowledgeRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String ref = Http.required(req, "ack_reference");
        return idempotency.execute(idempotencyKey, req, OutboundFile.class,
                () -> ResponseEntity.ok(crud.read(outbound.acknowledge(id, ref).toString(), OutboundFile.class)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> outboundFilesDownload(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok(Http.object(json, outbound.downloadLink(id, Http::absolute)));
    }
}
