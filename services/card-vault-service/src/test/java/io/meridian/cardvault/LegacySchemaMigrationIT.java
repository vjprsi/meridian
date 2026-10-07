package io.meridian.cardvault;

import static org.assertj.core.api.Assertions.assertThat;

import io.meridian.test.MeridianTestContainers;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * GAP-03: the local migration on a database created before the change (stored CVV2, no CVK / PIN key per record, no
 * envelope columns): CVV2 values are overwritten and the column dropped, every record gets the CVK it was vaulted
 * under, key rows gain the envelope columns, and the re-wrap job can enumerate tenants. Runs in its own database.
 */
class LegacySchemaMigrationIT {
    static final String T1 = "0199a000-0000-7000-8000-0000000f0001";
    static final String T2 = "0199a000-0000-7000-8000-0000000f0002";

    @Test
    void storedCvv2IsRemovedAndRecordsNameTheirCvk() throws Exception {
        String admin = MeridianTestContainers.adminJdbcUrl();
        try (Connection c = DriverManager.getConnection(admin, "meridian", "meridian"); Statement s = c.createStatement()) {
            s.execute("drop database if exists card_vault_legacy_it");
            s.execute("create database card_vault_legacy_it");
        }
        String url = admin.replaceFirst("/[^/?]+(\\?|$)", "/card_vault_legacy_it$1");
        try (Connection c = DriverManager.getConnection(url, "meridian", "meridian"); Statement s = c.createStatement()) {
            // the pre-change shape (the columns the migration touches)
            s.execute("""
                    create schema card_vault;
                    set search_path to card_vault;
                    create table card_vault_key (id uuid primary key, tenant_id uuid not null, key_type varchar(32) not null,
                      version int not null, created_at timestamptz not null);
                    create table card_data_vault (id uuid primary key, tenant_id uuid not null, created_at timestamptz not null,
                      cvv_encrypted bytea, pin_encrypted bytea);
                    insert into card_vault_key values
                      ('00000000-0000-0000-0000-000000000001', '%1$s', 'CVK', 1, '2026-01-01'),
                      ('00000000-0000-0000-0000-000000000002', '%1$s', 'CVK', 2, '2026-06-01'),
                      ('00000000-0000-0000-0000-000000000003', '%2$s', 'CVK', 1, '2026-01-01');
                    insert into card_data_vault values
                      ('00000000-0000-0000-0000-00000000000a', '%1$s', '2026-03-01', '\\x01020304', null),
                      ('00000000-0000-0000-0000-00000000000b', '%1$s', '2026-07-01', '\\x05060708', '\\x0909'),
                      ('00000000-0000-0000-0000-00000000000c', '%2$s', '2026-03-01', '\\x0a0b0c0d', null);
                    """.formatted(T1, T2));
            String migration;
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream("db/migration/V001_60__cde_keys_and_sad_removal.sql")) {
                migration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            s.execute(migration);
            s.execute(migration); // idempotent

            try (ResultSet rs = s.executeQuery("select count(*) from information_schema.columns where table_schema = "
                    + "'card_vault' and table_name = 'card_data_vault' and column_name = 'cvv_encrypted'")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
            try (ResultSet rs = s.executeQuery("select id::text, cvk_key_id::text from card_vault.card_data_vault order by id")) {
                rs.next();
                assertThat(rs.getString(2)).isEqualTo("00000000-0000-0000-0000-000000000001"); // vaulted under CVK v1
                rs.next();
                assertThat(rs.getString(2)).isEqualTo("00000000-0000-0000-0000-000000000002"); // after the rotation
                rs.next();
                assertThat(rs.getString(2)).isEqualTo("00000000-0000-0000-0000-000000000003"); // own tenant's CVK
            }
            try (ResultSet rs = s.executeQuery("select count(*) from information_schema.columns where table_schema = "
                    + "'card_vault' and column_name in ('wrapped_key', 'master_key_ref', 'wrapped_at', 'pin_key_id')")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(4);
            }
            try (ResultSet rs = s.executeQuery("select count(*) from card_vault.card_vault_key_tenants()")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }
    }
}
