package io.meridian.filegateway.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.filegateway.api.generated.FileChannelsApi;
import io.meridian.filegateway.api.generated.model.FileChannel;
import io.meridian.filegateway.api.generated.model.FileChannelCreate;
import io.meridian.filegateway.api.generated.model.FileChannelUpdate;
import io.meridian.filegateway.api.generated.model.FileChannelsDisableRequest;
import io.meridian.filegateway.api.generated.model.FileChannelsRotateCredentialsRequest;
import io.meridian.filegateway.application.ChannelService;
import io.meridian.filegateway.application.InboundService;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * File channels. Secret references ({@code credential_secret_ref}, {@code pgp_encrypt_key_ref},
 * {@code pgp_decrypt_key_ref}) are write-only and accepted from the request body although the generated
 * models omit them (they are {@code secret} columns).
 */
@RestController
public class FileChannelsController implements FileChannelsApi {
    private static final String TABLE = "file_channel";

    private final ChannelService channels;
    private final InboundService inbound;
    private final CrudEngine crud;
    private final Updates updates;
    private final Idempotency idempotency;
    private final JsonMapper json;

    public FileChannelsController(ChannelService channels, InboundService inbound, CrudEngine crud, Updates updates,
                                  Idempotency idempotency, JsonMapper json) {
        this.channels = channels;
        this.inbound = inbound;
        this.crud = crud;
        this.updates = updates;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public ResponseEntity<FileChannel> fileChannelsCreate(String idempotencyKey, FileChannelCreate body) {
        TenantContext.require();
        JsonNode raw = Http.rawBody(json);
        return idempotency.execute(idempotencyKey, raw, FileChannel.class, () -> {
            ObjectNode values = crud.toColumns(TABLE, body);
            channels.applySecrets(values, raw, true);
            channels.validate(values);
            return ResponseEntity.status(HttpStatus.CREATED).body(crud.read(crud.insert(TABLE, values), FileChannel.class));
        });
    }

    @Override
    public ResponseEntity<FileChannel> fileChannelsUpdate(UUID id, String ifMatch, FileChannelUpdate body) {
        TenantContext.require();
        JsonNode raw = Http.rawBody(json);
        ObjectNode changes = crud.toColumns(TABLE, body);
        channels.applySecrets(changes, raw, false);
        return updates.apply(TABLE, id, ifMatch, changes, FileChannel.class, (merged, ch) -> {
            channels.validate(merged);
            if (merged.hasNonNull("port") && !ch.has("port")) {
                ch.set("port", merged.get("port"));
            }
        });
    }

    @Override
    public ResponseEntity<Void> fileChannelsTestConnection(UUID id, String idempotencyKey) {
        TenantContext.require();
        return Http.json(idempotency.execute(idempotencyKey, json.createObjectNode().put("test", id.toString()),
                JsonNode.class, () -> ResponseEntity.ok(channels.testConnection(id))));
    }

    @Override
    public ResponseEntity<Void> fileChannelsPollNow(UUID id, String idempotencyKey) {
        TenantContext.require();
        return Http.json(idempotency.execute(idempotencyKey, json.createObjectNode().put("poll", id.toString()),
                JsonNode.class, () -> ResponseEntity.ok(channels.pollNow(id, inbound))));
    }

    @Override
    public ResponseEntity<FileChannel> fileChannelsRotateCredentials(UUID id, String idempotencyKey,
                                                                     FileChannelsRotateCredentialsRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        return idempotency.execute(idempotencyKey, req, FileChannel.class, () -> ResponseEntity.ok(crud.read(
                channels.rotateCredentials(id, Http.required(req, "credential_secret_ref")), FileChannel.class)));
    }

    @Override
    public ResponseEntity<FileChannel> fileChannelsDisable(UUID id, String idempotencyKey, FileChannelsDisableRequest body) {
        TenantContext.require();
        JsonNode req = json.valueToTree(body);
        String reason = Http.required(req, "reason");
        return idempotency.execute(idempotencyKey, req, FileChannel.class,
                () -> ResponseEntity.ok(crud.read(channels.disable(id, reason), FileChannel.class)));
    }
}
