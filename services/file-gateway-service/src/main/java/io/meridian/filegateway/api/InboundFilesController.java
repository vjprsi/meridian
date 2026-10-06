package io.meridian.filegateway.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.filegateway.api.generated.InboundFilesApi;
import io.meridian.filegateway.api.generated.model.FileTransferLogPage;
import io.meridian.filegateway.api.generated.model.InboundFile;
import io.meridian.filegateway.api.generated.model.InboundFilesReleaseQuarantineRequest;
import io.meridian.filegateway.api.generated.model.InboundFilesUploadRequest;
import io.meridian.filegateway.application.FormatService;
import io.meridian.filegateway.application.InboundService;
import io.meridian.filegateway.infrastructure.ContentFetcher;
import io.meridian.filegateway.infrastructure.Rows;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inbound files pushed to us ({@code POST /upload}) or received by polling. The upload carries the file itself:
 * {@code content} (text), {@code content_base64} or {@code content_url} (fetched by the gateway).
 */
@RestController
public class InboundFilesController implements InboundFilesApi {
    private final InboundService inbound;
    private final ContentFetcher fetcher;
    private final Rows rows;
    private final CrudEngine crud;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public InboundFilesController(InboundService inbound, ContentFetcher fetcher, Rows rows, CrudEngine crud,
                                  Idempotency idempotency, JsonMapper json) {
        this.inbound = inbound;
        this.fetcher = fetcher;
        this.rows = rows;
        this.crud = crud;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<InboundFile> inboundFilesUpload(String idempotencyKey, InboundFilesUploadRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, req, InboundFile.class, () -> {
            JsonNode channel = rows.require("file_channel", UUID.fromString(req.get("file_channel_id").asString()), false);
            byte[] content = FormatService.inlineContent(req);
            if (content == null && req.hasNonNull("content_url")) {
                content = fetcher.fetch(req.get("content_url").asString());
            }
            JsonNode row = inbound.receive(channel, Http.text(req, "original_file_name"), Http.text(req, "remote_path"),
                    content, req, "UPLOAD");
            return ResponseEntity.ok(crud.read(row.toString(), InboundFile.class));
        });
    }

    @Override
    public ResponseEntity<InboundFile> inboundFilesReprocess(UUID id, String idempotencyKey) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, json.createObjectNode().put("reprocess", id.toString()), InboundFile.class,
                () -> ResponseEntity.ok(crud.read(inbound.reprocess(id).toString(), InboundFile.class)));
    }

    @Override
    public ResponseEntity<InboundFile> inboundFilesReleaseQuarantine(UUID id, String idempotencyKey,
                                                                     InboundFilesReleaseQuarantineRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Http.required(req, "reason");
        return idempotency.execute(idempotencyKey, req, InboundFile.class,
                () -> ResponseEntity.ok(crud.read(inbound.releaseQuarantine(id, reason).toString(), InboundFile.class)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> inboundFilesDownload(UUID id) {
        TenantContext.require();
        return ResponseEntity.ok(Http.object(json, inbound.downloadLink(id, Http::absolute)));
    }

    @Override
    public ResponseEntity<FileTransferLogPage> inboundFilesLogs(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        rows.requireText("inbound_file", id, false);
        return crud.list("file_transfer_log", FileTransferLogPage.class,
                Crud.params().with("inbound_file_id", id).with("limit", limit).with("cursor", cursor));
    }
}
