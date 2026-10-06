package io.meridian.filegateway.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.filegateway.domain.FileRules;
import io.meridian.filegateway.infrastructure.ChannelConnector;
import io.meridian.filegateway.infrastructure.JsonValues;
import io.meridian.filegateway.infrastructure.Rows;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * File channels: configuration rules per protocol (credentials only as secret-store references),
 * connectivity tests with health tracking, inbound polling, credential rotation and disabling.
 */
@Service
public class ChannelService {
    /** Write-only secret references (not part of the API model; read from the raw request body). */
    public static final List<String> SECRET_FIELDS = List.of("credential_secret_ref", "pgp_encrypt_key_ref", "pgp_decrypt_key_ref");
    private static final Set<String> SERVER_PROTOCOLS = Set.of("SFTP", "FTPS", "AS2", "CONNECT_DIRECT");

    private final Rows rows;
    private final ChannelConnector connector;
    private final TransferLog log;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public ChannelService(Rows rows, ChannelConnector connector, TransferLog log, OutboxWriter outbox,
                          TransactionTemplate tx, JsonMapper json) {
        this.rows = rows;
        this.connector = connector;
        this.log = log;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
    }

    /** Copies secret references from the raw body into the column values, validating their form. */
    public void applySecrets(ObjectNode values, JsonNode raw, boolean creating) {
        for (String f : SECRET_FIELDS) {
            if (raw.hasNonNull(f)) {
                FileRules.requireSecretRef(f, raw.get(f).asString(), false);
                values.put(f, raw.get(f).asString());
            }
        }
        if (creating) {
            FileRules.requireSecretRef("credential_secret_ref", JsonValues.text(values, "credential_secret_ref"), true);
        }
        for (String forbidden : List.of("password", "private_key", "secret", "access_key", "secret_key")) {
            if (raw.has(forbidden)) {
                throw ApiException.unprocessable("literal_credential_rejected",
                        "Credentials are never accepted inline; store them in the vault and send credential_secret_ref");
            }
        }
    }

    /** Protocol-specific configuration rules on the (merged) channel values. */
    public void validate(ObjectNode v) {
        String protocol = JsonValues.text(v, "protocol");
        String host = JsonValues.text(v, "host");
        if (protocol == null) {
            throw ApiException.unprocessable("protocol_required", "protocol is required");
        }
        if (host == null || host.isBlank()) {
            throw ApiException.unprocessable("host_required", protocol + " channels need a host, bucket or base URL");
        }
        if (SERVER_PROTOCOLS.contains(protocol)) {
            if (!v.hasNonNull("port")) {
                v.put("port", switch (protocol) {
                    case "SFTP" -> 22;
                    case "FTPS" -> 990;
                    case "AS2" -> 443;
                    default -> 1364;
                });
            }
            if (("SFTP".equals(protocol) || "FTPS".equals(protocol)) && JsonValues.text(v, "username") == null) {
                throw ApiException.unprocessable("username_required", protocol + " channels need a username");
            }
        }
        if (v.hasNonNull("port") && (v.get("port").asInt() < 1 || v.get("port").asInt() > 65535)) {
            throw ApiException.unprocessable("invalid_port", "port must be between 1 and 65535");
        }
        if ("HTTPS_API".equals(protocol) && !host.startsWith("https://")) {
            throw ApiException.unprocessable("insecure_endpoint", "HTTPS_API channels need an https:// base URL");
        }
        String fp = JsonValues.text(v, "host_key_fingerprint");
        if (fp != null && !fp.matches("SHA256:[A-Za-z0-9+/]{43}=?")) {
            throw ApiException.unprocessable("invalid_host_key_fingerprint",
                    "host_key_fingerprint must be an OpenSSH SHA256 fingerprint (SHA256:<base64>)");
        }
        if (JsonValues.text(v, "file_name_pattern") != null) {
            FileRules.regex("file_name_pattern", v.get("file_name_pattern").asString());
        }
        String cron = JsonValues.text(v, "poll_cron");
        if (cron != null) {
            if (!FileRules.receives(JsonValues.text(v, "direction"))) {
                throw ApiException.unprocessable("poll_requires_inbound", "poll_cron applies only to inbound channels");
            }
            try {
                CronExpression.parse(cron);
            } catch (IllegalArgumentException e) {
                throw ApiException.unprocessable("invalid_cron", "poll_cron is not a valid cron expression");
            }
        }
        JsonValues.strings(v.get("ip_allowlist")).forEach(FileRules::requireIpOrCidr);
        if (v.path("pgp_sign").asBoolean(false) && JsonValues.text(v, "pgp_decrypt_key_ref") == null) {
            throw ApiException.unprocessable("signing_key_missing", "pgp_sign needs our private key (pgp_decrypt_key_ref)");
        }
    }

    public JsonNode testConnection(UUID id) {
        JsonNode channel = rows.require("file_channel", id, false);
        requireActive(channel);
        ChannelConnector.ConnectionResult r = connector.test(channel);
        String previous = channel.path("health_status").asString(null);
        String health = r.success() ? "UP" : "DOWN";
        recordHealth(id, previous, health, r.success());
        log.append(new TransferLog.Entry(id, null, null, "CONNECT", r.success(), r.remoteAddress(), null, r.latencyMs(),
                r.message()));
        ObjectNode out = json.createObjectNode();
        out.put("file_channel_id", id.toString());
        out.put("success", r.success());
        out.put("health_status", health);
        out.put("previous_health_status", previous);
        out.put("latency_ms", r.latencyMs());
        out.put("remote_address", r.remoteAddress());
        out.put("message", r.message());
        out.put("tested_at", OffsetDateTime.now().toString());
        return out;
    }

    /** Lists the remote inbound folder and receives every new file matching the channel's name pattern. */
    public JsonNode pollNow(UUID id, InboundService inbound) {
        JsonNode channel = rows.require("file_channel", id, false);
        requireActive(channel);
        if (!FileRules.receives(channel.get("direction").asString())) {
            throw ApiException.conflict("channel_not_inbound", "Channel " + channel.get("code").asString() + " is outbound only");
        }
        List<ChannelConnector.RemoteFile> files;
        try {
            files = connector.list(channel);
        } catch (ChannelConnector.TransferException e) {
            recordHealth(id, channel.path("health_status").asString(null), "DOWN", false);
            log.inbound(id, null, "LIST", false, null, e.getMessage());
            throw new ApiException(502, "channel_unreachable", e.getMessage());
        }
        log.inbound(id, null, "LIST", true, null, files.size() + " file(s) in " + channel.path("remote_inbound_path").asString("/"));
        String pattern = JsonValues.text(channel, "file_name_pattern");
        ArrayNode received = json.createArrayNode();
        List<String> duplicates = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int skipped = 0;
        for (ChannelConnector.RemoteFile f : files) {
            if (pattern != null && !FileRules.matches(pattern, f.name())) {
                skipped++;
                continue;
            }
            try {
                byte[] content = connector.download(channel, f);
                JsonNode row = inbound.receive(channel, f.name(), f.path(), content, json.createObjectNode(), "DOWNLOAD");
                received.add(row.get("id").asString());
                if (channel.path("delete_after_download").asBoolean(false)) {
                    connector.deleteRemote(channel, f);
                    log.inbound(id, UUID.fromString(row.get("id").asString()), "DELETE_REMOTE", true, null, f.path());
                }
            } catch (ApiException e) {
                if ("duplicate_file".equals(e.code())) {
                    duplicates.add(f.name());
                } else {
                    failed.add(f.name() + ": " + e.code());
                }
            } catch (ChannelConnector.TransferException e) {
                failed.add(f.name() + ": " + e.getMessage());
                log.inbound(id, null, "DOWNLOAD", false, null, f.path() + ": " + e.getMessage());
            }
        }
        tx.executeWithoutResult(s -> rows.update("update file_channel set last_connected_at = now() where id = ?", id));
        ObjectNode out = json.createObjectNode();
        out.put("file_channel_id", id.toString());
        out.put("files_listed", files.size());
        out.put("files_skipped", skipped);
        out.set("inbound_file_ids", received);
        out.put("files_received", received.size());
        ArrayNode d = out.putArray("duplicates");
        duplicates.forEach(d::add);
        ArrayNode fl = out.putArray("failed");
        failed.forEach(fl::add);
        return out;
    }

    public String rotateCredentials(UUID id, String ref) {
        FileRules.requireSecretRef("credential_secret_ref", ref, true);
        String row = tx.execute(s -> {
            JsonNode channel = rows.require("file_channel", id, true);
            if (Objects.equals(channel.path("credential_secret_ref").asString(null), ref)) {
                throw ApiException.unprocessable("credential_unchanged", "The new credential reference equals the current one");
            }
            return rows.updateReturning("""
                    update file_channel set credential_secret_ref = ?, updated_by = ? where id = ?
                    returning row_to_json(file_channel)::text""", ref, TenantContext.principal(), id);
        });
        log.append(new TransferLog.Entry(id, null, null, "CONNECT", true, null, null, null,
                "Credentials rotated to a new secret reference"));
        return row;
    }

    public String disable(UUID id, String reason) {
        String row = tx.execute(s -> {
            JsonNode channel = rows.require("file_channel", id, true);
            if (!channel.path("is_active").asBoolean(true)) {
                throw FileRules.transition("Channel", "DISABLED", "be disabled");
            }
            String r = rows.updateReturning("""
                    update file_channel set is_active = false, updated_by = ? where id = ?
                    returning row_to_json(file_channel)::text""", TenantContext.principal(), id);
            long pending = rows.queryOne("""
                    select json_build_object('n', count(*))::text from outbound_file
                    where file_channel_id = ? and status in ('REQUESTED','GENERATING','GENERATED','ENCRYPTING','QUEUED')""", id)
                    .map(n -> n.get("n").asLong()).orElse(0L);
            if (pending > 0) {
                rows.update("""
                        update outbound_file set status = 'CANCELLED', error = ?
                        where file_channel_id = ? and status in ('REQUESTED','GENERATING','GENERATED','ENCRYPTING','QUEUED')""",
                        "Channel disabled: " + reason, id);
            }
            return r;
        });
        log.append(new TransferLog.Entry(id, null, null, "CONNECT", true, null, null, null, "Channel disabled: " + reason));
        return row;
    }

    private void recordHealth(UUID id, String previous, String health, boolean connected) {
        tx.executeWithoutResult(s -> {
            String row = rows.updateReturning("""
                    update file_channel set health_status = ?,
                           last_connected_at = case when ? then now() else last_connected_at end
                    where id = ? returning row_to_json(file_channel)::text""", health, connected, id);
            if (!Objects.equals(previous, health)) {
                JsonNode n = json.readTree(row);
                ObjectNode payload = ((ObjectNode) n).deepCopy();
                SECRET_FIELDS.forEach(payload::remove);
                payload.put("previous_health_status", previous);
                outbox.publish("operations.file_channel.health_changed", "file_channel", id, payload);
            }
        });
    }

    static void requireActive(JsonNode channel) {
        if (!channel.path("is_active").asBoolean(true)) {
            throw ApiException.conflict("channel_disabled", "Channel " + channel.path("code").asString() + " is disabled");
        }
    }
}
