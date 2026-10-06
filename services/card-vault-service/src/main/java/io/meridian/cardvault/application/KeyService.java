package io.meridian.cardvault.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Key metadata (card_vault_key). One active key per key type and tenant; the default keys (PAN_DEK, CVK, ZPK ...)
 * are bootstrapped on first use under a transaction-scoped advisory lock, so concurrent first requests create one.
 */
@Service
public class KeyService {
    /** Default HSM labels of bootstrapped keys. */
    static final Map<String, String> DEFAULT_LABELS = Map.of(
            "PAN_DEK", "pan-dek", "CVK", "cvk", "ZPK", "zpk-default", "PIN_KEY", "pin-key", "CVV_KEY", "cvv-key",
            "MDK", "mdk");

    public record Key(UUID id, String type, String label, int version, boolean active) {
        public Hsm.KeyRef ref() {
            return new Hsm.KeyRef(label, version);
        }
    }

    private final JdbcClient jdbc;
    private final Hsm hsm;

    public KeyService(JdbcClient jdbc, Hsm hsm) {
        this.jdbc = jdbc;
        this.hsm = hsm;
    }

    /** The active key of a type, created (version 1, default label) when the tenant has none. */
    @Transactional
    public Key active(String type) {
        Optional<Key> k = findActive(type);
        if (k.isPresent()) {
            return k.get();
        }
        lock(type);
        return findActive(type).orElseGet(() -> insert(type, DEFAULT_LABELS.getOrDefault(type, type.toLowerCase()), 1,
                "AES-256-GCM", null));
    }

    public Key byId(UUID id) {
        return jdbc.sql("select id, key_type, hsm_key_label, version, is_active from card_vault_key where id = ?")
                .param(id).query(KeyService::map).optional()
                .orElseThrow(() -> ApiException.notFound("card vault key", id));
    }

    /**
     * Resolves the zone key a PIN block is wrapped under: the key's uuid, or an hsm_key_label (latest active
     * version). Unknown keys are 404 not_found; a key of another type is rejected.
     */
    @Transactional
    public Key resolveZoneKey(String idOrLabel) {
        if (idOrLabel == null || idOrLabel.isBlank()) {
            throw ApiException.unprocessable("invalid_pin_key", "pin_key_id is required");
        }
        if (DEFAULT_LABELS.get("ZPK").equals(idOrLabel)) {
            active("ZPK");
        }
        Optional<Key> k;
        try {
            UUID id = UUID.fromString(idOrLabel);
            k = jdbc.sql("select id, key_type, hsm_key_label, version, is_active from card_vault_key where id = ?")
                    .param(id).query(KeyService::map).optional();
        } catch (IllegalArgumentException notUuid) {
            k = jdbc.sql("""
                    select id, key_type, hsm_key_label, version, is_active from card_vault_key
                    where hsm_key_label = ? and is_active order by version desc limit 1""")
                    .param(idOrLabel).query(KeyService::map).optional();
        }
        Key key = k.orElseThrow(() -> ApiException.notFound("zone key", idOrLabel));
        if (!"ZPK".equals(key.type())) {
            throw ApiException.unprocessable("invalid_pin_key", "pin_key_id must reference a ZPK");
        }
        if (!key.active()) {
            throw ApiException.unprocessable("key_inactive", "zone key " + idOrLabel + " is retired");
        }
        return key;
    }

    /**
     * Registers a key from the API: version = next for (type, label); it becomes the active key of its type and
     * previous active keys of the type are retired.
     */
    @Transactional
    public Key create(String type, String label, String algorithm, OffsetDateTime retireAfter) {
        if (label == null || label.isBlank() || label.length() > 128) {
            throw ApiException.unprocessable("invalid_key_label", "hsm_key_label must be 1-128 characters");
        }
        lock(type);
        int version = nextVersion(type, label);
        retire(type);
        return insert(type, label, version, algorithm == null ? "AES-256-GCM" : algorithm, retireAfter);
    }

    /** Rotation: next version of the active key's label becomes active; the old version is retired now. */
    @Transactional
    public Key rotate(String type) {
        Key current = active(type);
        lock(type);
        int version = nextVersion(type, current.label());
        retire(type);
        return insert(type, current.label(), version, "AES-256-GCM", null);
    }

    private int nextVersion(String type, String label) {
        int max = jdbc.sql("select coalesce(max(version), 0) from card_vault_key where key_type = ? and hsm_key_label = ?")
                .params(type, label).query(Integer.class).single();
        return max + 1;
    }

    private void retire(String type) {
        jdbc.sql("""
                update card_vault_key set is_active = false, retire_after = now(), updated_by = ?
                where key_type = ? and is_active""").params(TenantContext.principal(), type).update();
    }

    private Optional<Key> findActive(String type) {
        return jdbc.sql("""
                select id, key_type, hsm_key_label, version, is_active from card_vault_key
                where key_type = ? and is_active order by version desc, created_at desc limit 1""")
                .param(type).query(KeyService::map).optional();
    }

    private void lock(String type) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(?))")
                .param("card_vault_key:" + TenantContext.require() + ":" + type).query().singleRow();
    }

    private Key insert(String type, String label, int version, String algorithm, OffsetDateTime retireAfter) {
        String kcv = hsm.kcv(new Hsm.KeyRef(label, version));
        UUID id = jdbc.sql("""
                insert into card_vault_key (tenant_id, created_by, updated_by, key_type, hsm_key_label, kcv, algorithm,
                  version, active_from, retire_after, is_active)
                values (?::uuid, ?, ?, ?, ?, ?, ?, ?, now(), ?, true) returning id""")
                .params(TenantContext.require().toString(), TenantContext.principal(), TenantContext.principal(), type,
                        label, kcv, algorithm, version, retireAfter)
                .query(UUID.class).single();
        return new Key(id, type, label, version, true);
    }

    private static Key map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Key(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBoolean(5));
    }
}
