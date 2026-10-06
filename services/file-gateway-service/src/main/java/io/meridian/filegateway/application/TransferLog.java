package io.meridian.filegateway.application;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Appends to the immutable file transfer audit trail. */
@Component
public class TransferLog {
    private final CrudEngine crud;
    private final JsonMapper json;

    public TransferLog(CrudEngine crud, JsonMapper json) {
        this.crud = crud;
        this.json = json;
    }

    public record Entry(UUID channelId, UUID inboundFileId, UUID outboundFileId, String action, boolean success,
                        String remoteAddress, Long bytes, Long durationMs, String message) {}

    public void append(Entry e) {
        ObjectNode v = json.createObjectNode();
        if (e.channelId() != null) {
            v.put("file_channel_id", e.channelId().toString());
        }
        if (e.inboundFileId() != null) {
            v.put("inbound_file_id", e.inboundFileId().toString());
        }
        if (e.outboundFileId() != null) {
            v.put("outbound_file_id", e.outboundFileId().toString());
        }
        v.put("action", e.action());
        v.put("success", e.success());
        if (e.remoteAddress() != null) {
            v.put("remote_address", truncate(e.remoteAddress(), 64));
        }
        if (e.bytes() != null) {
            v.put("bytes_transferred", e.bytes());
        }
        if (e.durationMs() != null) {
            v.put("duration_ms", (int) Math.min(Integer.MAX_VALUE, e.durationMs()));
        }
        if (e.message() != null) {
            v.put("message", truncate(e.message(), 1000));
        }
        v.put("actor", truncate(TenantContext.principal(), 64));
        crud.insert("file_transfer_log", v);
    }

    public void inbound(UUID channel, UUID file, String action, boolean success, Long bytes, String message) {
        append(new Entry(channel, file, null, action, success, null, bytes, null, message));
    }

    public void outbound(UUID channel, UUID file, String action, boolean success, Long bytes, Long ms, String message) {
        append(new Entry(channel, null, file, action, success, null, bytes, ms, message));
    }

    private static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
