package io.meridian.filegateway.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.filegateway.domain.FileRules;
import io.meridian.filegateway.domain.LayoutValidator;
import io.meridian.filegateway.domain.VirusScanner;
import io.meridian.filegateway.infrastructure.FileStore;
import io.meridian.filegateway.infrastructure.JsonValues;
import io.meridian.filegateway.infrastructure.Rows;
import io.meridian.web.client.ServiceClient;
import io.meridian.web.crud.CrudEngine;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Inbound file pipeline: receive (store write-once, SHA-256 dedupe per channel) -> route match -> virus scan
 * (infected files are quarantined) -> layout validation -> dispatch to the consumer service -> acknowledge.
 * Every step is written to the immutable transfer log and state changes publish their events.
 */
@Service
public class InboundService {
    private final Rows rows;
    private final CrudEngine crud;
    private final FileStore store;
    private final RouteService routes;
    private final FormatService formats;
    private final ServiceClient services;
    private final TransferLog log;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final long maxBytes;

    public InboundService(Rows rows, CrudEngine crud, FileStore store, RouteService routes, FormatService formats,
                          ServiceClient services, TransferLog log, OutboxWriter outbox, TransactionTemplate tx,
                          JsonMapper json, @Value("${meridian.files.max-size-bytes:26214400}") long maxBytes) {
        this.rows = rows;
        this.crud = crud;
        this.store = store;
        this.routes = routes;
        this.formats = formats;
        this.services = services;
        this.log = log;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
        this.maxBytes = maxBytes;
    }

    /**
     * Receives a file on a channel and runs the pipeline. {@code extra} may carry an explicit
     * {@code file_route_id}, {@code file_format_definition_id} and {@code document_id}.
     * {@code action} is the transfer action recorded (UPLOAD when pushed to us, DOWNLOAD when polled).
     */
    public JsonNode receive(JsonNode channel, String fileName, String remotePath, byte[] content, JsonNode extra,
                            String action) {
        UUID channelId = UUID.fromString(channel.get("id").asString());
        ChannelService.requireActive(channel);
        if (!FileRules.receives(channel.get("direction").asString())) {
            throw ApiException.conflict("channel_not_inbound", "Channel " + channel.get("code").asString() + " is outbound only");
        }
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) {
            throw ApiException.unprocessable("invalid_file_name", "original_file_name must be a plain file name");
        }
        String pattern = JsonValues.text(channel, "file_name_pattern");
        if (pattern != null && !FileRules.matches(pattern, fileName)) {
            throw ApiException.unprocessable("file_name_rejected", fileName + " does not match the channel's file name pattern");
        }
        if (content == null) {
            throw ApiException.unprocessable("content_required", "Provide content, content_base64 or content_url");
        }
        if (content.length == 0) {
            throw ApiException.unprocessable("empty_file", "The file is empty");
        }
        if (content.length > maxBytes) {
            throw ApiException.unprocessable("file_too_large", "File exceeds " + maxBytes + " bytes");
        }
        String checksum = FileStore.sha256(content);
        rows.queryOne("select row_to_json(f)::text from inbound_file f where file_channel_id = ? and checksum_sha256 = ?",
                channelId, checksum).ifPresent(dup -> {
                    log.inbound(channelId, UUID.fromString(dup.get("id").asString()), action, false, (long) content.length,
                            "Duplicate of " + dup.get("id").asString() + " rejected (" + fileName + ")");
                    throw ApiException.conflict("duplicate_file", "The same content (SHA-256 " + checksum
                            + ") was already received on this channel as inbound file " + dup.get("id").asString());
                });
        UUID id = Ids.newId();
        // scan in memory before anything touches disk: infected content only ever goes to the quarantine area
        String uri = VirusScanner.scan(content).infected()
                ? store.putQuarantined(TenantContext.require(), id, fileName, content)
                : store.put(TenantContext.require(), "inbound", id, fileName, content);
        tx.executeWithoutResult(s -> {
            ObjectNode v = json.createObjectNode();
            v.put("id", id.toString());
            v.put("file_channel_id", channelId.toString());
            v.put("original_file_name", fileName);
            if (remotePath != null) {
                v.put("remote_path", remotePath);
            }
            for (String f : List.of("file_route_id", "file_format_definition_id", "document_id")) {
                if (extra.hasNonNull(f)) {
                    v.put(f, extra.get(f).asString());
                }
            }
            v.put("storage_uri", uri);
            v.put("size_bytes", content.length);
            v.put("checksum_sha256", checksum);
            v.put("status", "RECEIVED");
            v.put("virus_scan_status", "PENDING");
            JsonNode row = json.readTree(crud.insert("inbound_file", v));
            outbox.publish("operations.inbound_file.received", "inbound_file", id, row);
        });
        log.inbound(channelId, id, action, true, (long) content.length, "Received " + fileName + " (sha256 " + checksum + ")");
        return process(id, true);
    }

    /** Runs routing, scanning, validation and dispatch for a stored file; returns the final row. */
    public JsonNode process(UUID id, boolean scan) {
        JsonNode file = rows.require("inbound_file", id, false);
        UUID channelId = UUID.fromString(file.get("file_channel_id").asString());
        byte[] content = store.get(file.get("storage_uri").asString());
        Optional<JsonNode> route = file.hasNonNull("file_route_id")
                ? rows.text("file_route", UUID.fromString(file.get("file_route_id").asString()), false).map(rows::tree)
                        .filter(r -> r.path("is_active").asBoolean(true))
                : routes.match(channelId, file.get("original_file_name").asString());
        boolean scanRequired = route.map(r -> r.path("require_virus_scan").asBoolean(true)).orElse(true);

        if (scan && scanRequired) {
            update(id, "update inbound_file set status = 'SCANNING' where id = ?", id);
            VirusScanner.Result r = VirusScanner.scan(content);
            log.inbound(channelId, id, "SCAN", !r.infected(), (long) content.length,
                    r.infected() ? "Infected: " + r.detail() : "Clean");
            if (r.infected()) {
                return publish("operations.inbound_file.quarantined", update(id, """
                        update inbound_file set status = 'QUARANTINED', virus_scan_status = 'INFECTED',
                               virus_scan_detail = ?, error = ? where id = ?""",
                        r.detail(), "Quarantined: " + r.detail(), id));
            }
            update(id, "update inbound_file set virus_scan_status = ?, virus_scan_detail = null where id = ?", r.status(), id);
        } else if (!scanRequired && "PENDING".equals(file.path("virus_scan_status").asString())) {
            update(id, "update inbound_file set virus_scan_status = 'SKIPPED' where id = ?", id);
        }

        if (route.isEmpty()) {
            log.inbound(channelId, id, "ROUTE", false, null, "No active route matches " + file.get("original_file_name").asString());
            return tree(update(id, """
                    update inbound_file set status = 'REJECTED', error = 'No active route matches the file' where id = ?""", id));
        }
        JsonNode rt = route.get();
        UUID routeId = UUID.fromString(rt.get("id").asString());
        String formatId = rt.hasNonNull("file_format_definition_id") ? rt.get("file_format_definition_id").asString()
                : file.hasNonNull("file_format_definition_id") ? file.get("file_format_definition_id").asString() : null;
        Integer recordCount = null;
        JsonNode format = null;
        if (formatId != null) {
            update(id, "update inbound_file set status = 'VALIDATING', file_format_definition_id = ?::uuid where id = ?", formatId, id);
            format = rows.require("file_format_definition", UUID.fromString(formatId), false);
            LayoutValidator.Result v = formats.validate(format, content);
            recordCount = v.recordCount();
            log.inbound(channelId, id, "VALIDATE", v.valid(), null, v.valid()
                    ? v.recordCount() + " record(s) valid against " + format.get("code").asString()
                    : v.errorCount() + " layout error(s) against " + format.get("code").asString());
            if (!v.valid()) {
                String summary = v.errors().stream().limit(5)
                        .map(e -> "line " + e.line() + (e.field() == null ? "" : " " + e.field()) + ": " + e.code())
                        .reduce((a, b) -> a + "; " + b).orElse("invalid");
                return tree(update(id, """
                        update inbound_file set status = 'REJECTED', record_count = ?, file_route_id = ?, error = ?
                        where id = ?""", recordCount, routeId, v.errorCount() + " layout error(s): " + summary, id));
            }
        }
        return dispatch(file, rt, format, recordCount, channelId);
    }

    private JsonNode dispatch(JsonNode file, JsonNode route, JsonNode format, Integer recordCount, UUID channelId) {
        UUID id = UUID.fromString(file.get("id").asString());
        UUID routeId = UUID.fromString(route.get("id").asString());
        String target = route.get("target_service").asString();
        String topic = route.get("target_topic").asString();
        ObjectNode payload = json.createObjectNode();
        payload.put("inbound_file_id", id.toString());
        payload.put("file_channel_id", channelId.toString());
        payload.put("original_file_name", file.get("original_file_name").asString());
        payload.put("checksum_sha256", file.get("checksum_sha256").asString());
        payload.put("size_bytes", file.get("size_bytes").asLong());
        payload.put("storage_uri", file.get("storage_uri").asString());
        payload.put("received_at", file.get("received_at").asString());
        if (recordCount != null) {
            payload.put("record_count", recordCount);
        }
        if (format != null) {
            payload.put("file_format_code", format.get("code").asString());
        }
        payload.put("target_topic", topic);
        String consumerRef = null;
        boolean processed = false;
        if (topic.startsWith("/")) {
            long attempt = rows.queryOne("""
                    select json_build_object('n', count(*))::text from file_transfer_log
                    where inbound_file_id = ? and action = 'ROUTE'""", id).map(n -> n.get("n").asLong()).orElse(0L) + 1;
            try {
                JsonNode res = services.post(target, topic, payload, "ifl-" + id + "-" + attempt);
                if (res != null) {
                    consumerRef = JsonValues.text(res, "consumer_reference") != null ? res.get("consumer_reference").asString()
                            : JsonValues.text(res, "reference") != null ? res.get("reference").asString()
                            : JsonValues.text(res, "id");
                    String st = JsonValues.text(res, "status");
                    processed = "PROCESSED".equals(st) || "COMPLETED".equals(st);
                }
            } catch (ApiException e) {
                log.inbound(channelId, id, "ERROR", false, null, target + " rejected the file: " + e.code() + " " + e.getMessage());
                return tree(update(id, """
                        update inbound_file set status = 'FAILED', file_route_id = ?, record_count = ?, error = ?
                        where id = ?""", routeId, recordCount, "Dispatch to " + target + " failed: " + e.code(), id));
            }
        }
        String ref = consumerRef;
        log.inbound(channelId, id, "ROUTE", true, null, "Routed to " + target + " " + topic);
        JsonNode routed = publish("operations.inbound_file.routed", update(id, """
                update inbound_file set status = 'ROUTED', file_route_id = ?, record_count = ?, consumer_reference = ?,
                       error = null where id = ?""", routeId, recordCount, ref, id));
        if (!processed) {
            return routed;
        }
        log.inbound(channelId, id, "ACK", true, null, target + " acknowledged processing" + (ref == null ? "" : " as " + ref));
        return publish("operations.inbound_file.processed", update(id,
                "update inbound_file set status = 'PROCESSED', processed_at = now() where id = ?", id));
    }

    public JsonNode reprocess(UUID id) {
        JsonNode file = rows.require("inbound_file", id, false);
        FileRules.requireReprocess(file.get("status").asString());
        String scanStatus = file.path("virus_scan_status").asString();
        update(id, "update inbound_file set status = 'RECEIVED', error = null where id = ?", id);
        log.inbound(UUID.fromString(file.get("file_channel_id").asString()), id, "ROUTE", true, null, "Reprocessing requested");
        return process(id, !"CLEAN".equals(scanStatus) && !"SKIPPED".equals(scanStatus));
    }

    /** Releases a quarantined file after review (false positive); the releaser must not be the receiver. */
    public JsonNode releaseQuarantine(UUID id, String reason) {
        JsonNode file = tx.execute(s -> {
            JsonNode f = rows.require("inbound_file", id, true);
            FileRules.requireQuarantined(f.get("status").asString());
            if (Objects.equals(f.path("created_by").asString(null), TenantContext.principal())) {
                throw ApiException.unprocessable("four_eyes_violation",
                        "Quarantine release must be approved by a different user than the one who received the file");
            }
            rows.update("""
                    update inbound_file set status = 'RECEIVED', virus_scan_status = 'CLEAN', virus_scan_detail = ?,
                           error = null where id = ?""",
                    truncate("Released by " + TenantContext.principal() + ": " + reason + " (was "
                            + f.path("virus_scan_detail").asString("") + ")", 500), id);
            return f;
        });
        log.inbound(UUID.fromString(file.get("file_channel_id").asString()), id, "SCAN", true, null,
                "Quarantine released by " + TenantContext.principal() + ": " + reason);
        return process(id, false);
    }

    public JsonNode downloadLink(UUID id, UnaryOperator<String> absolute) {
        JsonNode file = rows.require("inbound_file", id, false);
        if ("QUARANTINED".equals(file.get("status").asString())) {
            throw ApiException.conflict("file_quarantined", "Quarantined files cannot be downloaded");
        }
        FileStore.SignedUrl url = store.sign(file.get("storage_uri").asString());
        ObjectNode out = json.createObjectNode();
        out.put("inbound_file_id", id.toString());
        out.put("file_name", file.get("original_file_name").asString());
        out.put("url", absolute.apply(url.path()));
        out.put("expires_at", url.expiresAt().toString());
        out.put("checksum_sha256", file.get("checksum_sha256").asString());
        out.put("size_bytes", file.get("size_bytes").asLong());
        return out;
    }

    private String update(UUID id, String sql, Object... params) {
        return tx.execute(s -> {
            rows.update(sql, params);
            return rows.requireText("inbound_file", id, false);
        });
    }

    private JsonNode publish(String type, String row) {
        JsonNode n = json.readTree(row);
        tx.executeWithoutResult(s -> outbox.publish(type, "inbound_file", UUID.fromString(n.get("id").asString()), n));
        return n;
    }

    private JsonNode tree(String row) {
        return json.readTree(row);
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
