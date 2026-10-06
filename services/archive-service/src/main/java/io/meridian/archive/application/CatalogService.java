package io.meridian.archive.application;

import io.meridian.archive.domain.ArchiveRules;
import io.meridian.archive.domain.CatalogAdvisor;
import io.meridian.archive.infrastructure.JsonValues;
import io.meridian.archive.infrastructure.Rows;
import io.meridian.archive.infrastructure.ServiceCatalog;
import io.meridian.archive.infrastructure.SourceClient;
import io.meridian.archive.infrastructure.WormStore;
import io.meridian.archive.domain.Certificate;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Catalogue, policies and archived objects: classification rules and discovery from the spec catalogue,
 * retention-policy rules and preview, archive-object verification (checksum + object lock) and restore.
 */
@Service
public class CatalogService {
    private static final Set<String> LOW = Set.of("PUBLIC", "INTERNAL");

    private final Rows rows;
    private final CrudEngine crud;
    private final ServiceCatalog services;
    private final SourceClient source;
    private final WormStore worm;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public CatalogService(Rows rows, CrudEngine crud, ServiceCatalog services, SourceClient source, WormStore worm,
                          TransactionTemplate tx, JsonMapper json) {
        this.rows = rows;
        this.crud = crud;
        this.services = services;
        this.source = source;
        this.worm = worm;
        this.tx = tx;
        this.json = json;
    }

    // ------------------------------------------------------------------ classifications

    public void validateClassification(JsonNode v) {
        String service = JsonValues.text(v, "service_name");
        if (service == null || !services.exists(service)) {
            throw ApiException.unprocessable("unknown_service", "service_name " + service + " is not a Meridian service");
        }
        String dataClass = JsonValues.text(v, "data_class");
        if (dataClass == null || !dataClass.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw ApiException.unprocessable("invalid_data_class", "data_class must be an upper-case code, e.g. TRANSACTION");
        }
        List<String> pii = JsonValues.strings(v.get("pii_columns"));
        if (!pii.isEmpty() && LOW.contains(JsonValues.text(v, "sensitivity"))) {
            throw ApiException.unprocessable("sensitivity_too_low", "A table with personal data cannot be classified "
                    + JsonValues.text(v, "sensitivity"));
        }
    }

    /**
     * Discovers tables from the spec catalogue and suggests a data class and sensitivity for each; with
     * {@code apply} the suggestions for unclassified tables are stored.
     */
    public JsonNode scan(boolean apply) {
        List<CatalogAdvisor.Table> tables = CatalogAdvisor.parse(catalogue());
        Set<String> existing = new HashSet<>();
        rows.query("select row_to_json(c)::text from data_classification c")
                .forEach(c -> existing.add(c.get("database_name").asString() + "." + c.get("table_name").asString()));
        ObjectNode out = json.createObjectNode();
        ArrayNode suggestions = out.putArray("suggestions");
        int created = 0;
        int unclassified = 0;
        for (CatalogAdvisor.Table t : tables) {
            CatalogAdvisor.Suggestion s = CatalogAdvisor.suggest(t);
            boolean classified = existing.contains(t.database() + "." + t.table());
            ObjectNode n = suggestions.addObject();
            n.put("service_name", t.service());
            n.put("database_name", t.database());
            n.put("table_name", t.table());
            n.put("suggested_data_class", s.dataClass());
            n.put("suggested_sensitivity", s.sensitivity());
            ArrayNode pii = n.putArray("pii_columns");
            t.piiColumns().forEach(pii::add);
            n.put("already_classified", classified);
            if (!classified) {
                unclassified++;
                if (apply) {
                    ObjectNode v = json.createObjectNode();
                    v.put("data_class", s.dataClass());
                    v.put("service_name", t.service());
                    v.put("database_name", t.database());
                    v.put("table_name", t.table());
                    v.put("sensitivity", s.sensitivity());
                    v.set("pii_columns", pii.deepCopy());
                    v.put("description", "Suggested by catalogue scan");
                    tx.executeWithoutResult(x -> crud.insert("data_classification", v));
                    n.put("created", true);
                    created++;
                }
            }
        }
        out.put("tables_scanned", tables.size());
        out.put("unclassified", unclassified);
        out.put("created", created);
        out.put("applied", apply);
        return out;
    }

    private static String catalogue() {
        ClassPathResource r = new ClassPathResource("meridian/data-dictionary.md");
        if (!r.exists()) {
            throw new ApiException(503, "catalogue_unavailable", "The spec catalogue is not packaged with this service");
        }
        try (InputStream in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(503, "catalogue_unavailable", "Cannot read the spec catalogue");
        }
    }

    // ------------------------------------------------------------------ retention policies

    public void validatePolicy(JsonNode v, UUID selfId) {
        ArchiveRules.requirePolicyShape(v.path("online_days").asInt(365), v.path("retention_years").asInt(0));
        String dataClass = JsonValues.text(v, "data_class");
        if (dataClass == null || !dataClass.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw ApiException.unprocessable("invalid_data_class", "data_class must be an upper-case code, e.g. TRANSACTION");
        }
        String country = JsonValues.text(v, "country_code");
        if (country != null && !country.matches("[A-Z]{2}")) {
            throw ApiException.unprocessable("invalid_country_code", "country_code must be ISO 3166 alpha-2");
        }
        if (v.path("is_active").asBoolean(true)) {
            boolean clash = rows.query("""
                    select row_to_json(p)::text from retention_policy p where is_active and data_class = ?
                    and coalesce(country_code, '') = coalesce(?, '') and coalesce(legal_entity_id::text, '') = coalesce(?, '')""",
                    dataClass, country, JsonValues.text(v, "legal_entity_id"))
                    .stream().anyMatch(p -> selfId == null || !p.get("id").asString().equals(selfId.toString()));
            if (clash) {
                throw ApiException.conflict("policy_exists", "An active policy already covers " + dataClass
                        + (country == null ? "" : " in " + country) + "; deactivate it first");
            }
        }
    }

    /** Counts what the policy would archive and purge today, per classified table (asks the owning services). */
    public JsonNode preview(UUID policyId) {
        JsonNode policy = rows.require("retention_policy", policyId, false);
        LocalDate today = LocalDate.now();
        LocalDate archiveCutoff = ArchiveRules.archiveCutoff(today, policy.get("online_days").asInt());
        LocalDate purgeCutoff = ArchiveRules.purgeCutoff(today, policy.get("retention_years").asInt());
        ObjectNode out = json.createObjectNode();
        out.put("retention_policy_id", policyId.toString());
        out.put("data_class", policy.get("data_class").asString());
        out.put("action", policy.get("action").asString());
        out.put("archive_cutoff_date", archiveCutoff.toString());
        out.put("purge_cutoff_date", purgeCutoff.toString());
        ArrayNode tables = out.putArray("tables");
        long archivable = 0;
        long purgeable = 0;
        for (JsonNode c : rows.query("select row_to_json(c)::text from data_classification c where data_class = ? order by table_name",
                policy.get("data_class").asString())) {
            ObjectNode t = tables.addObject();
            t.put("data_classification_id", c.get("id").asString());
            t.put("service_name", c.get("service_name").asString());
            t.put("table_name", c.get("table_name").asString());
            try {
                long a = count(c, archiveCutoff);
                long p = count(c, purgeCutoff);
                t.put("archivable", a);
                t.put("purgeable", p);
                archivable += a;
                purgeable += p;
            } catch (ApiException e) {
                t.put("error", e.code() + ": " + e.getMessage());
            }
        }
        out.put("total_archivable", archivable);
        out.put("total_purgeable", purgeable);
        return out;
    }

    private long count(JsonNode cls, LocalDate before) {
        ObjectNode body = json.createObjectNode();
        body.put("database_name", cls.get("database_name").asString());
        body.put("table_name", cls.get("table_name").asString());
        body.put("created_before", before.toString());
        JsonNode res = source.count(cls.get("service_name").asString(), body, null);
        return res == null ? 0 : res.path("count").asLong(0);
    }

    // ------------------------------------------------------------------ archived objects

    /** Recomputes the checksum and checks the object lock against the manifest. */
    public JsonNode verify(UUID objectId) {
        JsonNode o = rows.require("archived_object", objectId, false);
        String uri = o.get("storage_uri").asString();
        ObjectNode out = json.createObjectNode();
        out.put("archived_object_id", objectId.toString());
        out.put("storage_uri", uri);
        out.put("checksum_expected", o.get("checksum_sha256").asString());
        WormStore.LockState lock = worm.lockState(uri);
        boolean checksumOk = false;
        boolean countOk = false;
        if (lock.exists()) {
            byte[] content = worm.get(uri);
            String actual = Certificate.sha256(content);
            out.put("checksum_actual", actual);
            checksumOk = actual.equals(o.get("checksum_sha256").asString());
            long lines = new String(content, StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).count();
            countOk = lines == o.get("record_count").asLong();
        }
        LocalDate expected = LocalDate.parse(o.get("worm_retain_until").asString());
        ObjectNode l = out.putObject("object_lock");
        l.put("exists", lock.exists());
        l.put("read_only", lock.readOnly());
        l.put("locked_until", lock.lockedUntil() == null ? null : lock.lockedUntil().toString());
        l.put("compliant", lock.compliant(expected));
        out.put("checksum_match", checksumOk);
        out.put("record_count_match", countOk);
        out.put("valid", checksumOk && countOk && lock.compliant(expected));
        out.put("verified_at", io.meridian.common.time.Clocks.now().toString());
        return out;
    }

    /** Restores a verified batch to the query area (read-only investigations); the archive itself is untouched. */
    public JsonNode restore(UUID objectId, String reason) {
        JsonNode o = rows.require("archived_object", objectId, false);
        if (!o.path("restorable").asBoolean(true)) {
            throw ApiException.conflict("not_restorable", "Archive object " + objectId + " is not restorable");
        }
        byte[] content = worm.get(o.get("storage_uri").asString());
        if (!Certificate.sha256(content).equals(o.get("checksum_sha256").asString())) {
            throw ApiException.conflict("integrity_check_failed", "Checksum mismatch: the archive object may have been tampered with");
        }
        String location = worm.restore(TenantContext.require(), objectId, content);
        ObjectNode out = json.createObjectNode();
        out.put("archived_object_id", objectId.toString());
        out.put("restored_to", location);
        out.put("record_count", o.get("record_count").asLong());
        out.put("reason", reason);
        out.put("restored_by", TenantContext.principal());
        out.put("restored_at", io.meridian.common.time.Clocks.now().toString());
        out.put("expires_at", io.meridian.common.time.Clocks.now().plusDays(30).toString());
        return out;
    }
}
