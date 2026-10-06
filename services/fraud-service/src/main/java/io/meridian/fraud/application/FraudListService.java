package io.meridian.fraud.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.fraud.domain.ListValues;
import io.meridian.fraud.infrastructure.Db;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Block / allow / watch lists: values are stored hashed (SHA-256 of the normalised value) with a masked copy. */
@Service
public class FraudListService {
    private final Db db;
    private final OutboxWriter outbox;
    private final FraudConfigCache cache;
    private final JsonMapper json;

    public FraudListService(Db db, OutboxWriter outbox, FraudConfigCache cache, JsonMapper json) {
        this.db = db;
        this.outbox = outbox;
        this.cache = cache;
        this.json = json;
    }

    /** Adds (or refreshes) a value; re-adding an existing value updates reason, source and expiry. */
    @Transactional
    public String addEntry(UUID listId, String value, String reason, String source, String expiresAt) {
        JsonNode list = db.require("fraud_list", listId, false);
        if (value == null || value.isBlank()) {
            throw ApiException.unprocessable("value_required", "value is required");
        }
        String type = list.get("entity_type").asString();
        if (expiresAt != null && !OffsetDateTime.parse(expiresAt).isAfter(OffsetDateTime.now())) {
            throw ApiException.unprocessable("invalid_expiry", "expires_at must be in the future");
        }
        String row = db.one("""
                insert into fraud_list_entry as e (tenant_id, created_by, updated_by, fraud_list_id, value_hash, value_masked,
                    reason, source, expires_at)
                values (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, coalesce(?, 'ANALYST'), cast(? as timestamptz))
                on conflict (fraud_list_id, value_hash) do update set reason = excluded.reason, source = excluded.source,
                    expires_at = excluded.expires_at, updated_by = excluded.updated_by
                returning row_to_json(e)::text""",
                TenantContext.principal(), TenantContext.principal(), listId, ListValues.hash(type, value),
                ListValues.mask(type, value), reason, source, expiresAt)
                .map(db::text).orElseThrow();
        JsonNode n = json.readTree(row);
        outbox.publish("risk.fraud_list_entry.added", "fraud_list_entry", UUID.fromString(n.get("id").asString()), n);
        return row;
    }

    public Map<String, Object> lookup(UUID listId, String value) {
        JsonNode list = db.require("fraud_list", listId, false);
        String type = list.get("entity_type").asString();
        var entry = db.one("""
                select row_to_json(e)::text from fraud_list_entry e
                where fraud_list_id = ? and value_hash = ? and (expires_at is null or expires_at > now())""",
                listId, ListValues.hash(type, value));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list_id", listId.toString());
        out.put("list_code", list.get("code").asString());
        out.put("list_type", list.get("list_type").asString());
        out.put("entity_type", type);
        out.put("list_active", list.get("is_active").asBoolean());
        out.put("on_list", entry.isPresent());
        out.put("value_masked", ListValues.mask(type, value));
        entry.ifPresent(e -> {
            out.put("entry_id", e.get("id").asString());
            out.put("reason", Db.str(e, "reason"));
            out.put("source", Db.str(e, "source"));
            out.put("expires_at", Db.str(e, "expires_at"));
            out.put("added_at", Db.str(e, "created_at"));
        });
        return out;
    }

    /** Deleting a list removes its entries first (entries cannot outlive their list). */
    @Transactional
    public void deleteList(UUID listId) {
        db.require("fraud_list", listId, true);
        db.update("delete from fraud_list_entry where fraud_list_id = ?", listId);
        db.update("delete from fraud_list where id = ?", listId);
        cache.invalidate();
    }

    public void changed() {
        cache.invalidate();
    }
}
