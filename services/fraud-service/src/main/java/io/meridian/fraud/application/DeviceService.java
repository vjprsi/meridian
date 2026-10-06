package io.meridian.fraud.application;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.fraud.infrastructure.Db;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Device trust and blocking. A blocked device blocks every evaluation that uses it. */
@Service
public class DeviceService {
    private final Db db;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public DeviceService(Db db, OutboxWriter outbox, JsonMapper json) {
        this.db = db;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String trust(UUID id, String reason) {
        JsonNode d = db.require("device_fingerprint", id, true);
        if (Db.bool(d, "blocked")) {
            throw ApiException.conflict("device_blocked", "A blocked device cannot be trusted; unblock it first");
        }
        if (Db.bool(d, "is_emulator")) {
            throw ApiException.unprocessable("emulator_not_trustable", "Emulators cannot be marked trusted");
        }
        return change(id, "update device_fingerprint set trusted = true, status_reason = ? where id = ?",
                reason, "risk.device.trusted");
    }

    @Transactional
    public String block(UUID id, String reason) {
        JsonNode d = db.require("device_fingerprint", id, true);
        if (Db.bool(d, "blocked")) {
            throw ApiException.conflict("invalid_transition", "Device is already blocked");
        }
        return change(id, "update device_fingerprint set blocked = true, trusted = false, risk_score = 1000, status_reason = ? where id = ?",
                reason, "risk.device.blocked");
    }

    @Transactional
    public String unblock(UUID id, String reason) {
        JsonNode d = db.require("device_fingerprint", id, true);
        if (!Db.bool(d, "blocked")) {
            throw ApiException.conflict("invalid_transition", "Device is not blocked");
        }
        return change(id, "update device_fingerprint set blocked = false, risk_score = 0, status_reason = ? where id = ?",
                reason, "risk.device.unblocked");
    }

    private String change(UUID id, String sql, String reason, String event) {
        db.update(sql, reason, id);
        JsonNode row = db.require("device_fingerprint", id, false);
        outbox.publish(event, "device_fingerprint", id, row);
        return db.text(row);
    }

    JsonNode tree(String s) {
        return json.readTree(s);
    }
}
