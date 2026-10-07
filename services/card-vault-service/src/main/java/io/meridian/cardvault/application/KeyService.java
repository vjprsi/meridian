package io.meridian.cardvault.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Key metadata (card_vault_key). One active key per key type and tenant; the default keys (PAN_DEK, CVK, ZPK,
 * PIN_KEY, FINGERPRINT ...) are bootstrapped on first use under a transaction-scoped advisory lock, so concurrent
 * first requests create one. GAP-01: the key material of every new key comes from the {@link Hsm} adapter - a data
 * key wrapped by the KMS master key (stored in {@code wrapped_key} with {@code master_key_ref}) or a key generated
 * inside the PKCS#11 HSM (nothing stored but metadata).
 */
@Service
public class KeyService {
    /** Default HSM labels of bootstrapped keys. */
    static final Map<String, String> DEFAULT_LABELS = Map.of(
            "PAN_DEK", "pan-dek", "CVK", "cvk", "ZPK", "zpk-default", "PIN_KEY", "pin-key", "CVV_KEY", "cvv-key",
            "MDK", "mdk", "FINGERPRINT", "fingerprint");

    /**
     * SEC-37: data-encryption key types whose bootstrapped / rotated labels are tenant-scoped
     * ({@code <label>@<tenant id>}), so no two tenants share key material for stored card data. Zone and scheme keys
     * (ZPK, CVK, MDK, CVV_KEY) are exchanged with third parties and keep their labels. Keys created before this change
     * keep their label until the next rotation, which moves them to the scoped label. (With envelope encryption every
     * tenant's key is a separate data key anyway; the scoped label keeps PKCS#11 token aliases apart.)
     */
    static final java.util.Set<String> TENANT_SCOPED = java.util.Set.of("PAN_DEK", "PIN_KEY");

    static String scoped(String type, String label) {
        if (!TENANT_SCOPED.contains(type) || label.contains("@")) {
            return label;
        }
        return label + "@" + io.meridian.common.tenancy.TenantContext.require();
    }

    public record Key(UUID id, String type, String label, int version, boolean active, byte[] wrapped,
                      String masterRef) {
        public Hsm.KeyRef ref() {
            return new Hsm.KeyRef(type, label, version, wrapped, masterRef);
        }
    }

    /** Outcome of a master key re-wrap for the tenant. */
    public record RewrapResult(String masterRef, int rewrapped, int adopted, int unchanged, List<UUID> keyIds) {}

    private static final String COLS = "id, key_type, hsm_key_label, version, is_active, wrapped_key, master_key_ref";

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
        return findActive(type).orElseGet(() -> insert(type,
                scoped(type, DEFAULT_LABELS.getOrDefault(type, type.toLowerCase())), 1, "AES-256-GCM", null));
    }

    public Key byId(UUID id) {
        return jdbc.sql("select " + COLS + " from card_vault_key where id = ?")
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
            k = jdbc.sql("select " + COLS + " from card_vault_key where id = ?")
                    .param(id).query(KeyService::map).optional();
        } catch (IllegalArgumentException notUuid) {
            k = jdbc.sql("select " + COLS + " from card_vault_key "
                            + "where hsm_key_label = ? and is_active order by version desc limit 1")
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
        if (label == null || label.isBlank() || label.length() > 80) {
            throw ApiException.unprocessable("invalid_key_label", "hsm_key_label must be 1-80 characters");
        }
        // SEC-37: platform labels and the tenant-scope separator are reserved
        if (label.contains("@") || DEFAULT_LABELS.containsValue(label) || label.toLowerCase(java.util.Locale.ROOT).startsWith("meridian")) {
            throw ApiException.unprocessable("reserved_key_label", "hsm_key_label " + label + " is reserved");
        }
        if ("FINGERPRINT".equals(type)) {
            throw ApiException.unprocessable("key_not_replaceable", "the PAN fingerprint key cannot be replaced: "
                    + "every stored fingerprint depends on it");
        }
        label = scoped(type, label);
        lock(type);
        int version = nextVersion(type, label);
        retire(type);
        return insert(type, label, version, algorithm == null ? "AES-256-GCM" : algorithm, retireAfter);
    }

    /** Rotation: next version of the active key's label becomes active; the old version is retired now. */
    @Transactional
    public Key rotate(String type) {
        if ("FINGERPRINT".equals(type)) {
            throw ApiException.unprocessable("key_not_rotatable", "the PAN fingerprint key is not rotated (lookups "
                    + "depend on it); master key rotation re-wraps it");
        }
        Key current = active(type);
        lock(type);
        String label = scoped(type, current.label());
        int version = nextVersion(type, label);
        retire(type);
        return insert(type, label, version, "AES-256-GCM", null);
    }

    /**
     * Master key rotation for the current tenant (GAP-01): every data key not yet wrapped under the current master
     * key is re-wrapped (KMS ReEncrypt), legacy derived keys are adopted (wrapped for the first time). Key material
     * and therefore every record encrypted under it are unchanged: no data re-encryption.
     */
    @Transactional
    public RewrapResult rewrapAll() {
        String current = hsm.currentMasterRef();
        int rewrapped = 0;
        int adopted = 0;
        int unchanged = 0;
        List<UUID> ids = new ArrayList<>();
        List<Key> all = jdbc.sql("select " + COLS + " from card_vault_key order by created_at for update")
                .query(KeyService::map).list();
        for (Key k : all) {
            boolean legacy = k.wrapped() == null && !current.equals(k.masterRef());
            if (!legacy && current.equals(k.masterRef())) {
                unchanged++;
                continue;
            }
            Hsm.KeyMaterial m = hsm.rewrap(k.ref());
            jdbc.sql("""
                    update card_vault_key set wrapped_key = ?, master_key_ref = ?, wrapped_at = ?, updated_by = ?
                    where id = ?""")
                    .params(m.wrapped(), m.masterRef(), Clocks.now(), TenantContext.principal(), k.id()).update();
            ids.add(k.id());
            if (legacy && m.wrapped() != null) {
                adopted++;
            } else {
                rewrapped++;
            }
        }
        return new RewrapResult(current, rewrapped, adopted, unchanged, ids);
    }

    /** Key row as JSON for events: metadata only, never the wrapped key. */
    public String eventRow(UUID id) {
        return jdbc.sql("select (to_jsonb(k) - 'wrapped_key')::text from card_vault_key k where id = ?")
                .param(id).query(String.class).single();
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
        return jdbc.sql("select " + COLS + " from card_vault_key "
                        + "where key_type = ? and is_active order by version desc, created_at desc limit 1")
                .param(type).query(KeyService::map).optional();
    }

    private void lock(String type) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(?))")
                .param("card_vault_key:" + TenantContext.require() + ":" + type).query().singleRow();
    }

    private Key insert(String type, String label, int version, String algorithm, OffsetDateTime retireAfter) {
        Hsm.KeyMaterial m = hsm.createKey(new Hsm.KeyRef(type, label, version, null, null));
        UUID id = jdbc.sql("""
                insert into card_vault_key (tenant_id, created_by, updated_by, key_type, hsm_key_label, kcv, algorithm,
                  version, active_from, retire_after, is_active, wrapped_key, master_key_ref, wrapped_at)
                values (?::uuid, ?, ?, ?, ?, ?, ?, ?, now(), ?, true, ?, ?, ?) returning id""")
                .params(TenantContext.require().toString(), TenantContext.principal(), TenantContext.principal(), type,
                        label, m.kcv(), algorithm, version, retireAfter, m.wrapped(), m.masterRef(), Clocks.now())
                .query(UUID.class).single();
        return new Key(id, type, label, version, true, m.wrapped(), m.masterRef());
    }

    private static Key map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Key(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBoolean(5),
                rs.getBytes(6), rs.getString(7));
    }
}
