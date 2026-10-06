package io.meridian.config.infrastructure;

import io.meridian.common.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL beyond the generic CRUD engine. Rows are returned as JSON text (row_to_json); RLS scopes tenant tables. */
@Repository
public class ConfigStore {
    private final JdbcClient jdbc;

    public ConfigStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ config entries

    public Optional<String> entry(UUID id, boolean forUpdate) {
        return jdbc.sql("select row_to_json(e)::text from config_entry e where id = ?" + (forUpdate ? " for update" : ""))
                .param(id).query(String.class).optional();
    }

    /** Serialises writers of one namespace/key (version allocation, activation) for the transaction. */
    public void lockKey(String namespace, String key) {
        jdbc.sql("select 1 from pg_advisory_xact_lock(hashtextextended(?, 0))")
                .param("config_entry|" + TenantContext.require() + "|" + namespace + "|" + key)
                .query(Integer.class).single();
    }

    public int maxVersion(String namespace, String key) {
        return jdbc.sql("select coalesce(max(version), 0) from config_entry where namespace = ? and config_key = ?")
                .params(namespace, key).query(Integer.class).single();
    }

    public Optional<String> active(String namespace, String key) {
        return jdbc.sql("""
                select row_to_json(e)::text from config_entry e
                where namespace = ? and config_key = ? and status = 'ACTIVE' order by version desc limit 1""")
                .params(namespace, key).query(String.class).optional();
    }

    /** Latest DRAFT or PENDING_APPROVAL version of a key, if any. */
    public Optional<String> latestOpen(String namespace, String key) {
        return jdbc.sql("""
                select row_to_json(e)::text from config_entry e
                where namespace = ? and config_key = ? and status in ('DRAFT', 'PENDING_APPROVAL')
                order by version desc limit 1""")
                .params(namespace, key).query(String.class).optional();
    }

    public List<String> activeEntries(List<String> namespaces) {
        if (namespaces.isEmpty()) {
            return jdbc.sql("""
                    select row_to_json(e)::text from config_entry e where status = 'ACTIVE'
                    order by namespace, config_key""").query(String.class).list();
        }
        return jdbc.sql("""
                select row_to_json(e)::text from config_entry e where status = 'ACTIVE' and namespace = any(?)
                order by namespace, config_key""")
                .param(namespaces.toArray(String[]::new)).query(String.class).list();
    }

    /** Latest version (any status) of every key in a namespace: source of schema-declared defaults. */
    public List<String> latestPerKey(String namespace) {
        return jdbc.sql("""
                select row_to_json(e)::text from (
                  select distinct on (config_key) * from config_entry where namespace = ?
                  order by config_key, version desc) e""")
                .param(namespace).query(String.class).list();
    }

    // ------------------------------------------------------------------ country packs

    public Optional<String> pack(UUID id) {
        return jdbc.sql("select row_to_json(p)::text from country_pack p where id = ?")
                .param(id).query(String.class).optional();
    }

    public Optional<String> install(UUID id, boolean forUpdate) {
        return jdbc.sql("select row_to_json(i)::text from tenant_country_pack i where id = ?" + (forUpdate ? " for update" : ""))
                .param(id).query(String.class).optional();
    }

    public void lockLegalEntity(UUID legalEntityId) {
        jdbc.sql("select 1 from pg_advisory_xact_lock(hashtextextended(?, 0))")
                .param("country_pack|" + TenantContext.require() + "|" + legalEntityId)
                .query(Integer.class).single();
    }

    /** Installs of the same country (any pack version) for a legal entity, excluding UNINSTALLED. */
    public List<String> liveInstallsForCountry(UUID legalEntityId, String country) {
        return jdbc.sql("""
                select row_to_json(i)::text from tenant_country_pack i join country_pack p on p.id = i.country_pack_id
                where i.legal_entity_id = ? and p.country = ? and i.status <> 'UNINSTALLED'""")
                .params(legalEntityId, country).query(String.class).list();
    }

    public Optional<String> installOf(UUID legalEntityId, UUID packId) {
        return jdbc.sql("select row_to_json(i)::text from tenant_country_pack i where legal_entity_id = ? and country_pack_id = ?")
                .params(legalEntityId, packId).query(String.class).optional();
    }

    public void deleteInstall(UUID id) {
        jdbc.sql("delete from tenant_country_pack where id = ?").param(id).update();
    }

    /** Installed packs (with pack content) relevant for resolving configuration. */
    public List<String> installedPacks(UUID legalEntityId, String country) {
        StringBuilder sql = new StringBuilder("""
                select json_build_object('install', row_to_json(i), 'pack', row_to_json(p))::text
                from tenant_country_pack i join country_pack p on p.id = i.country_pack_id
                where i.status in ('INSTALLED', 'UPGRADE_AVAILABLE')""");
        List<Object> args = new java.util.ArrayList<>();
        if (legalEntityId != null) {
            sql.append(" and i.legal_entity_id = ?");
            args.add(legalEntityId);
        }
        if (country != null) {
            sql.append(" and p.country = ?");
            args.add(country);
        }
        sql.append(" order by i.installed_at nulls first, i.id");
        return jdbc.sql(sql.toString()).params(args).query(String.class).list();
    }

    // ------------------------------------------------------------------ tax rules

    public int expirePackTaxRules(UUID packId, UUID legalEntityId) {
        int removed = jdbc.sql("""
                delete from tax_rate_rule where country_pack_id = ? and legal_entity_id = ? and valid_from >= current_date""")
                .params(packId, legalEntityId).update();
        int expired = jdbc.sql("""
                update tax_rate_rule set valid_to = current_date - 1
                where country_pack_id = ? and legal_entity_id = ? and (valid_to is null or valid_to >= current_date)""")
                .params(packId, legalEntityId).update();
        return removed + expired;
    }

    public List<String> taxRules(String country, String appliesTo, LocalDate on) {
        return jdbc.sql("""
                select row_to_json(t)::text from tax_rate_rule t
                where country = ? and applies_to = ? and valid_from <= ? and (valid_to is null or valid_to >= ?)
                order by tax_type, id""")
                .params(country, appliesTo, on, on).query(String.class).list();
    }

    // ------------------------------------------------------------------ custom fields

    public List<String> activeCustomFields(String tableName) {
        return jdbc.sql("""
                select row_to_json(d)::text from custom_field_definition d
                where table_name = ? and is_active order by slot""")
                .param(tableName).query(String.class).list();
    }

    public boolean fieldKeyTaken(String tableName, String fieldKey, UUID exceptId) {
        return jdbc.sql("""
                select count(*) from custom_field_definition
                where table_name = ? and field_key = ? and is_active and (?::uuid is null or id <> ?::uuid)""")
                .params(tableName, fieldKey, exceptId, exceptId).query(Integer.class).single() > 0;
    }
}
