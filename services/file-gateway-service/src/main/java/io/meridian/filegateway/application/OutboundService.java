package io.meridian.filegateway.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.filegateway.domain.FileRenderer;
import io.meridian.filegateway.domain.FileRules;
import io.meridian.filegateway.domain.LayoutValidator;
import io.meridian.filegateway.infrastructure.ChannelConnector;
import io.meridian.filegateway.infrastructure.FileStore;
import io.meridian.filegateway.infrastructure.JsonValues;
import io.meridian.filegateway.infrastructure.Rows;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Outbound files: generation (inline content or records rendered with the format layout, control totals),
 * optional PGP encryption, delivery over the channel with bounded retries and back-off, redelivery,
 * cancellation and counterparty acknowledgement.
 */
@Service
public class OutboundService {
    private final Rows rows;
    private final CrudEngine crud;
    private final FileStore store;
    private final FormatService formats;
    private final ChannelConnector connector;
    private final TransferLog log;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final int maxAttempts;
    private final Duration backoff;

    public OutboundService(Rows rows, CrudEngine crud, FileStore store, FormatService formats, ChannelConnector connector,
                           TransferLog log, OutboxWriter outbox, TransactionTemplate tx, JsonMapper json,
                           @Value("${meridian.files.delivery.max-attempts:3}") int maxAttempts,
                           @Value("${meridian.files.delivery.backoff:PT2S}") Duration backoff) {
        this.rows = rows;
        this.crud = crud;
        this.store = store;
        this.formats = formats;
        this.connector = connector;
        this.log = log;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoff = backoff;
    }

    /** Creates, generates and (unless scheduled for later) delivers the file. */
    public JsonNode create(ObjectNode values, JsonNode raw) {
        UUID channelId = UUID.fromString(values.get("file_channel_id").asString());
        JsonNode channel = rows.require("file_channel", channelId, false);
        ChannelService.requireActive(channel);
        if (!FileRules.sends(channel.get("direction").asString())) {
            throw ApiException.conflict("channel_not_outbound", "Channel " + channel.get("code").asString() + " is inbound only");
        }
        boolean encrypt = values.path("encrypt").asBoolean(true);
        if (encrypt && JsonValues.text(channel, "pgp_encrypt_key_ref") == null) {
            throw ApiException.unprocessable("encryption_key_missing",
                    "The channel has no counterparty PGP key (pgp_encrypt_key_ref); set encrypt=false or configure the key");
        }
        String fileName = values.get("file_name").asString();
        if (fileName.contains("/") || fileName.contains("\\") || fileName.isBlank()) {
            throw ApiException.unprocessable("invalid_file_name", "file_name must be a plain file name");
        }
        JsonNode format = values.hasNonNull("file_format_definition_id")
                ? rows.require("file_format_definition", UUID.fromString(values.get("file_format_definition_id").asString()), false)
                : null;
        byte[] content = FormatService.inlineContent(raw);
        int recordCount;
        BigDecimal controlSum = null;
        if (content == null && raw.has("records")) {
            FileRenderer.Rendered r = format == null
                    ? FileRenderer.render("JSON", null, null, null, false, raw.get("records"))
                    : FileRenderer.render(format.get("format_kind").asString(), format.get("schema_definition"),
                            format.hasNonNull("record_length") ? format.get("record_length").asInt() : null,
                            JsonValues.text(format, "delimiter"), format.path("has_header").asBoolean(false), raw.get("records"));
            content = r.content().getBytes(StandardCharsets.UTF_8);
            recordCount = r.recordCount();
            controlSum = r.controlSum();
        } else if (content != null) {
            recordCount = -1;
        } else {
            throw ApiException.unprocessable("content_required", "Provide records, content or content_base64 for the file");
        }
        if (content.length == 0) {
            throw ApiException.unprocessable("empty_file", "The generated file is empty");
        }
        if (format != null) {
            LayoutValidator.Result v = formats.validate(format, content);
            if (!v.valid()) {
                throw new ApiException(422, "invalid_content", v.errorCount() + " layout error(s) against "
                        + format.get("code").asString() + ": " + v.errors().getFirst().message());
            }
            recordCount = v.recordCount();
        }
        if (recordCount < 0) {
            recordCount = (int) new String(content, StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).count();
        }
        UUID id = Ids.newId();
        byte[] bytes = content;
        int records = recordCount;
        BigDecimal sum = controlSum;
        tx.executeWithoutResult(s -> {
            values.put("id", id.toString());
            values.put("status", "GENERATING");
            values.put("encrypt", encrypt);
            values.put("delivery_attempts", 0);
            crud.insert("outbound_file", values);
        });
        String uri = store.put(TenantContext.require(), "outbound", id, fileName, bytes);
        tx.executeWithoutResult(s -> {
            ObjectNode totals = json.createObjectNode();
            totals.put("record_count", records);
            if (sum != null) {
                totals.put("control_sum", sum.toPlainString());
            }
            String row = rows.updateReturning("""
                    update outbound_file set status = 'GENERATED', storage_uri = ?, size_bytes = ?, checksum_sha256 = ?,
                           record_count = ?, control_totals = ?::jsonb
                    where id = ? returning row_to_json(outbound_file)::text""",
                    uri, bytes.length, FileStore.sha256(bytes), records, json.writeValueAsString(totals), id);
            outbox.publish("operations.outbound_file.generated", "outbound_file", id, json.readTree(row));
        });
        JsonNode scheduled = values.get("scheduled_for");
        if (scheduled != null && !scheduled.isNull()
                && OffsetDateTime.parse(scheduled.asString()).isAfter(OffsetDateTime.now())) {
            return json.readTree(rows.updateReturning("""
                    update outbound_file set status = 'QUEUED' where id = ? returning row_to_json(outbound_file)::text""", id));
        }
        return deliver(id);
    }

    /** Delivers with up to {@code max-attempts} attempts and exponential back-off; FAILED after the last one. */
    public JsonNode deliver(UUID id) {
        JsonNode file = rows.require("outbound_file", id, false);
        UUID channelId = UUID.fromString(file.get("file_channel_id").asString());
        JsonNode channel = rows.require("file_channel", channelId, false);
        ChannelService.requireActive(channel);
        byte[] content = store.get(file.get("storage_uri").asString());
        if (file.path("encrypt").asBoolean(true)) {
            set(id, "update outbound_file set status = 'ENCRYPTING' where id = ?", id);
            log.outbound(channelId, id, "ENCRYPT", true, (long) content.length, null,
                    "Encrypted for " + channel.path("pgp_encrypt_key_ref").asString("?")
                            + (channel.path("pgp_sign").asBoolean(false) ? " and signed" : ""));
        }
        String fileName = file.get("file_name").asString();
        String lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            set(id, "update outbound_file set status = 'DELIVERING', delivery_attempts = delivery_attempts + 1 where id = ?", id);
            long start = System.nanoTime();
            try {
                connector.upload(channel, fileName, content);
                long ms = (System.nanoTime() - start) / 1_000_000;
                log.append(new TransferLog.Entry(channelId, null, id, "UPLOAD", true, channel.path("host").asString(null),
                        (long) content.length, ms, "Delivered " + fileName + " to "
                        + channel.path("remote_outbound_path").asString("/")));
                return publish("operations.outbound_file.delivered", set(id, """
                        update outbound_file set status = 'DELIVERED', delivered_at = now(), error = null where id = ?""", id));
            } catch (ChannelConnector.TransferException e) {
                lastError = e.getMessage();
                log.append(new TransferLog.Entry(channelId, null, id, "UPLOAD", false, channel.path("host").asString(null),
                        null, (System.nanoTime() - start) / 1_000_000, "Attempt " + attempt + " failed: " + e.getMessage()));
                set(id, "update outbound_file set error = ? where id = ?", e.getMessage(), id);
                if (attempt < maxAttempts) {
                    sleep(backoff.multipliedBy(1L << (attempt - 1)));
                }
            }
        }
        log.outbound(channelId, id, "ERROR", false, null, null, "Delivery failed after " + maxAttempts + " attempts");
        return publish("operations.outbound_file.failed", set(id,
                "update outbound_file set status = 'FAILED', error = ? where id = ?",
                "Delivery failed after " + maxAttempts + " attempts: " + lastError, id));
    }

    public JsonNode redeliver(UUID id, String reason) {
        JsonNode file = rows.require("outbound_file", id, false);
        FileRules.requireRedeliver(file.get("status").asString());
        log.outbound(UUID.fromString(file.get("file_channel_id").asString()), id, "UPLOAD", true, null, null,
                "Redelivery requested by " + TenantContext.principal() + (reason == null ? "" : ": " + reason));
        return deliver(id);
    }

    public JsonNode cancel(UUID id, String reason) {
        return tx.execute(s -> {
            JsonNode file = rows.require("outbound_file", id, true);
            FileRules.requireCancel(file.get("status").asString());
            return json.readTree(rows.updateReturning("""
                    update outbound_file set status = 'CANCELLED', error = ? where id = ?
                    returning row_to_json(outbound_file)::text""", "Cancelled by " + TenantContext.principal() + ": " + reason, id));
        });
    }

    public JsonNode acknowledge(UUID id, String ackReference) {
        JsonNode row = tx.execute(s -> {
            JsonNode file = rows.require("outbound_file", id, true);
            FileRules.requireAcknowledge(file.get("status").asString());
            return json.readTree(rows.updateReturning("""
                    update outbound_file set status = 'ACKNOWLEDGED', acknowledged_at = now(), ack_reference = ?
                    where id = ? returning row_to_json(outbound_file)::text""", ackReference, id));
        });
        log.outbound(UUID.fromString(row.get("file_channel_id").asString()), id, "ACK", true, null, null,
                "Counterparty acknowledgement " + ackReference);
        return row;
    }

    public JsonNode downloadLink(UUID id, UnaryOperator<String> absolute) {
        JsonNode file = rows.require("outbound_file", id, false);
        if (!file.hasNonNull("storage_uri")) {
            throw ApiException.conflict("file_not_generated", "The file has not been generated yet");
        }
        FileStore.SignedUrl url = store.sign(file.get("storage_uri").asString());
        ObjectNode out = json.createObjectNode();
        out.put("outbound_file_id", id.toString());
        out.put("file_name", file.get("file_name").asString());
        out.put("url", absolute.apply(url.path()));
        out.put("expires_at", url.expiresAt().toString());
        out.put("checksum_sha256", file.path("checksum_sha256").asString(null));
        out.put("size_bytes", file.path("size_bytes").asLong());
        return out;
    }

    private String set(UUID id, String sql, Object... params) {
        return tx.execute(s -> {
            rows.update(sql, params);
            return rows.requireText("outbound_file", id, false);
        });
    }

    private JsonNode publish(String type, String row) {
        JsonNode n = json.readTree(row);
        tx.executeWithoutResult(s -> outbox.publish(type, "outbound_file", UUID.fromString(n.get("id").asString()), n));
        return n;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
