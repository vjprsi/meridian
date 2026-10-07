package io.meridian.cardvault.infrastructure;

import io.meridian.common.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL over card_data_vault. Ciphertexts are only read into {@link Record}; API representations come from
 * {@link #row} which strips every encrypted column.
 */
@Repository
public class VaultStore {
    /** API view of a vault row: never contains ciphertexts or the tenant. */
    private static final String VIEW = "(to_jsonb(v) - 'pan_encrypted' - 'expiry_encrypted'"
            + " - 'pin_encrypted' - 'tenant_id')::text";
    private static final String COLS = "id, pan_token, pan_fingerprint, pan_encrypted, expiry_encrypted,"
            + " pin_encrypted, dek_key_id, cvk_key_id, pin_key_id, card_id, pin_set, pin_try_count, is_active";

    /**
     * A vault row with its ciphertexts. GAP-03: no CVV2 (derived under {@code cvkKeyId} on demand); {@code pin} is the
     * PIN block under the PIN encryption key {@code pinKeyId} (null pinKeyId with a pin: a legacy row whose PIN is
     * still encrypted under the DEK, converted on first use or by the next rotation).
     */
    public record Record(UUID id, String panToken, String fingerprint, byte[] pan, byte[] expiry, byte[] pin,
                         UUID dekKeyId, UUID cvkKeyId, UUID pinKeyId, UUID cardId, boolean pinSet, int pinTryCount,
                         boolean active) {
        public boolean legacyPin() {
            return pin != null && pinKeyId == null;
        }
    }

    public record NewRecord(String panToken, String fingerprint, byte[] pan, byte[] expiry, byte[] pin, UUID dekKeyId,
                            UUID cvkKeyId, UUID pinKeyId, UUID cardId, String bin, String last4, boolean pinSet,
                            int pinTryCount) {}

    private final JdbcClient jdbc;

    public VaultStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(NewRecord r) {
        return jdbc.sql("""
                insert into card_data_vault (tenant_id, created_by, updated_by, pan_token, pan_fingerprint, pan_encrypted,
                  expiry_encrypted, pin_encrypted, dek_key_id, cvk_key_id, pin_key_id, card_id, bin, last4, pin_set,
                  pin_try_count)
                values (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                .params(TenantContext.require().toString(), TenantContext.principal(), TenantContext.principal(),
                        r.panToken(), r.fingerprint(), r.pan(), r.expiry(), r.pin(), r.dekKeyId(), r.cvkKeyId(),
                        r.pinKeyId(), r.cardId(), r.bin(), r.last4(), r.pinSet(), r.pinTryCount())
                .query(UUID.class).single();
    }

    public String row(UUID id) {
        return jdbc.sql("select " + VIEW + " from card_data_vault v where id = ?").param(id).query(String.class).single();
    }

    public Optional<Record> byToken(String token) {
        return jdbc.sql("select " + COLS + " from card_data_vault where pan_token = ?").param(token)
                .query(VaultStore::map).optional();
    }

    /** Locks the row (concurrent PIN verifications must not lose try-count increments). */
    public Optional<Record> byTokenForUpdate(String token) {
        return jdbc.sql("select " + COLS + " from card_data_vault where pan_token = ? for update").param(token)
                .query(VaultStore::map).optional();
    }

    public Optional<Record> activeByCard(UUID cardId) {
        return jdbc.sql("select " + COLS + " from card_data_vault where card_id = ? and is_active"
                        + " order by created_at desc, id desc limit 1").param(cardId)
                .query(VaultStore::map).optional();
    }

    public List<Record> activeByFingerprint(String fingerprint) {
        return jdbc.sql("select " + COLS + " from card_data_vault where pan_fingerprint = ? and is_active"
                        + " order by created_at desc, id desc").param(fingerprint).query(VaultStore::map).list();
    }

    /** Tenants that hold vault keys (master key re-wrap job; SECURITY DEFINER function, ids only). */
    public List<UUID> keyTenants() {
        return jdbc.sql("select t from card_vault_key_tenants() t").query(UUID.class).list();
    }

    /** Records still encrypted under another key than {@code dekKeyId}, oldest first. */
    public List<Record> encryptedUnderOtherKey(UUID dekKeyId, int limit) {
        return jdbc.sql("select " + COLS + " from card_data_vault where dek_key_id <> ? order by id limit ? for update")
                .params(dekKeyId, limit).query(VaultStore::map).list();
    }

    public void reEncrypt(UUID id, byte[] pan, byte[] expiry, UUID dekKeyId) {
        jdbc.sql("""
                update card_data_vault set pan_encrypted = ?, expiry_encrypted = ?, dek_key_id = ?, updated_by = ?
                where id = ?""")
                .params(pan, expiry, dekKeyId, TenantContext.principal(), id).update();
    }

    /** Records whose PIN block is stored under another PIN key than {@code pinKeyId} (or legacy), oldest first. */
    public List<Record> pinUnderOtherKey(UUID pinKeyId, int limit) {
        return jdbc.sql("select " + COLS + " from card_data_vault where pin_encrypted is not null"
                        + " and (pin_key_id is null or pin_key_id <> ?) order by id limit ? for update")
                .params(pinKeyId, limit).query(VaultStore::map).list();
    }

    /** Replaces the stored PIN block form (PIN key rotation, legacy conversion) without touching the try counter. */
    public void storePinBlock(UUID id, byte[] pin, UUID pinKeyId) {
        jdbc.sql("update card_data_vault set pin_encrypted = ?, pin_key_id = ?, updated_by = ? where id = ?")
                .params(pin, pinKeyId, TenantContext.principal(), id).update();
    }

    public void touchDetokenized(UUID id) {
        jdbc.sql("update card_data_vault set last_detokenized_at = now(), updated_by = ? where id = ?")
                .params(TenantContext.principal(), id).update();
    }

    public void setPin(UUID id, byte[] pin, UUID pinKeyId) {
        jdbc.sql("""
                update card_data_vault set pin_encrypted = ?, pin_key_id = ?, pin_set = true, pin_try_count = 0,
                  last_detokenized_at = now(), updated_by = ? where id = ?""")
                .params(pin, pinKeyId, TenantContext.principal(), id).update();
    }

    /** SEC-24: records a CVV2 outcome; returns the consecutive failure count (0 after a match). */
    public int cvvOutcome(UUID id, boolean matched, int blockAfter) {
        if (matched) {
            jdbc.sql("update card_data_vault set cvv_fail_count = 0 where id = ? and cvv_blocked_at is null").param(id).update();
            return 0;
        }
        return jdbc.sql("""
                update card_data_vault set cvv_fail_count = cvv_fail_count + 1,
                  cvv_blocked_at = case when cvv_fail_count + 1 >= ? then coalesce(cvv_blocked_at, now()) else cvv_blocked_at end
                where id = ? returning cvv_fail_count""").params(blockAfter, id).query(Integer.class).single();
    }

    public boolean cvvBlocked(UUID id) {
        return jdbc.sql("select cvv_blocked_at is not null from card_data_vault where id = ?").param(id)
                .query(Boolean.class).single();
    }

    /** SEC-24: unblocks CVV2 checks (vault admin, e.g. after the card was reviewed or reissued). */
    public void resetCvvBlock(UUID id) {
        jdbc.sql("update card_data_vault set cvv_fail_count = 0, cvv_blocked_at = null where id = ?").param(id).update();
    }

    public void setPinTries(UUID id, int tries) {
        jdbc.sql("update card_data_vault set pin_try_count = ?, last_detokenized_at = now(), updated_by = ? where id = ?")
                .params(tries, TenantContext.principal(), id).update();
    }

    public void deactivate(UUID id) {
        jdbc.sql("update card_data_vault set is_active = false, updated_by = ? where id = ?")
                .params(TenantContext.principal(), id).update();
    }

    private static Record map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Record(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBytes(4), rs.getBytes(5),
                rs.getBytes(6), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class), rs.getObject(9, UUID.class),
                rs.getObject(10, UUID.class), rs.getBoolean(11), rs.getInt(12), rs.getBoolean(13));
    }
}
