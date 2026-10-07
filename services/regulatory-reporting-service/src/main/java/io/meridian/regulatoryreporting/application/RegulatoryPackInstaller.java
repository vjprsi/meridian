package io.meridian.regulatoryreporting.application;

import io.meridian.approvals.Approvals;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.common.time.Clocks;
import io.meridian.events.OutboxWriter;
import io.meridian.regulatoryreporting.domain.RegulatoryPack;
import io.meridian.regulatoryreporting.infrastructure.Store;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Installs or upgrades a {@link RegulatoryPack} into the current tenant's report definitions, idempotently:
 * <ul>
 *   <li>a report without a definition of the same code is created (DRAFT definition plus a DRAFT version; the version
 *       opens a publication request in central approvals, so a checker other than the installing user publishes it);</li>
 *   <li>a pack-owned definition whose pack-managed fields changed is updated ({@link RegulatoryPack#MANAGED};
 *       filing_offset_days and owner stay tenant-tuned);</li>
 *   <li>a pack-owned definition whose version content changed gets a new DRAFT version (effective from the pack's
 *       date, or from today when that is not after the version in force); older pack DRAFTs still waiting for
 *       publication are withdrawn so only the newest can be published;</li>
 *   <li>a tenant-defined definition with the same code (or one owned by another pack) is never touched (skipped);
 *       a pack version the checker rejected is not re-submitted (reported as {@code rejected});</li>
 *   <li>a pack-owned definition no longer in the pack is deprecated (retired).</li>
 * </ul>
 * {@code dry_run} reports the plan without writing anything or opening approval requests.
 */
@Service
public class RegulatoryPackInstaller {
    private final RegulatoryPackCatalog catalog;
    private final DefinitionService definitions;
    private final Store store;
    private final CrudEngine crud;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Approvals approvals;

    public RegulatoryPackInstaller(RegulatoryPackCatalog catalog, DefinitionService definitions, Store store,
                                   CrudEngine crud, OutboxWriter outbox, TransactionTemplate tx, JsonMapper json,
                                   Approvals approvals) {
        this.catalog = catalog;
        this.definitions = definitions;
        this.store = store;
        this.crud = crud;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
        this.approvals = approvals;
    }

    public ObjectNode catalog() {
        ObjectNode out = json.createObjectNode();
        ArrayNode data = out.putArray("data");
        catalog.all().forEach(p -> data.add(p.summary()));
        out.put("count", data.size());
        out.put("notice", "Report layouts, cell codes and GL mappings are ILLUSTRATIVE samples, not the regulators' "
                + "forms: validate them against the regulator's current taxonomy and instructions before use.");
        return out;
    }

    public ObjectNode install(String packCode, String version, UUID legalEntityId, boolean dryRun, boolean enabled) {
        TenantContext.require();
        RegulatoryPack pack = catalog.require(packCode);
        if (!enabled) {
            ObjectNode out = json.createObjectNode();
            out.put("pack_code", pack.code());
            out.put("version", pack.version());
            out.put("enabled", false);
            out.put("skipped", true);
            out.put("message", "Installation not requested (enabled=false); nothing changed");
            return out;
        }
        if (version != null && !version.isBlank() && !version.equals(pack.version())) {
            throw ApiException.unprocessable("unknown_pack_version", "This service ships " + pack.code() + " version "
                    + pack.version() + "; version " + version + " is not available");
        }
        // the plan compiles every version first: a pack that does not compile installs nothing
        pack.reports().forEach(r -> definitions.compile(r.version()));
        return tx.execute(status -> {
            ObjectNode result = apply(pack, legalEntityId, dryRun);
            if (dryRun) {
                status.setRollbackOnly();
            }
            return result;
        });
    }

    private ObjectNode apply(RegulatoryPack pack, UUID legalEntityId, boolean dryRun) {
        store.scalar("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) l", Integer.class,
                "regpack:" + TenantContext.require() + ":" + pack.code());
        Map<String, JsonNode> byCode = new LinkedHashMap<>();
        store.rows("select row_to_json(t)::text from report_definition t where t.pack_code = ? or t.code in ("
                        + String.join(",", java.util.Collections.nCopies(pack.reports().size(), "?")) + ") order by t.code",
                params(pack)).forEach(d -> byCode.put(d.get("code").asString(), d));

        ObjectNode out = json.createObjectNode();
        out.put("pack_code", pack.code());
        out.put("version", pack.version());
        out.put("country", pack.country());
        out.put("regulator", pack.regulator());
        out.put("dry_run", dryRun);
        out.put("enabled", true);
        ArrayNode created = out.putArray("created");
        ArrayNode updated = out.putArray("updated");
        ArrayNode unchanged = out.putArray("unchanged");
        ArrayNode skipped = out.putArray("skipped");
        ArrayNode retired = out.putArray("retired");
        ArrayNode pending = out.putArray("awaiting_publication");
        ArrayNode requestIds = out.putArray("approval_request_ids");

        for (RegulatoryPack.Report report : pack.reports()) {
            JsonNode def = byCode.get(report.code());
            if (def == null) {
                if (!dryRun) {
                    UUID id = createDefinition(pack, report);
                    DefinitionService.Added added = addVersion(pack, report, id, null);
                    pending.add(pendingEntry(report, added));
                    if (added.approvalRequestId() != null) {
                        requestIds.add(added.approvalRequestId().toString());
                    }
                }
                created.add(report.code());
                continue;
            }
            String owner = def.path("pack_code").asString(null);
            if (!pack.code().equals(owner)) {
                skipped.addObject().put("code", report.code())
                        .put("reason", owner == null ? "tenant_defined" : "owned_by_other_pack")
                        .put("message", owner == null ? "A tenant-defined report uses this code; it is left unchanged"
                                : "Report is owned by pack " + owner + "; it is left unchanged");
                continue;
            }
            if ("DEPRECATED".equals(def.path("status").asString())) {
                skipped.addObject().put("code", report.code()).put("reason", "deprecated")
                        .put("message", "The report was deprecated; the pack does not revive it");
                continue;
            }
            UUID id = UUID.fromString(def.get("id").asString());
            boolean defChanged = !report.definitionChecksum().equals(def.path("pack_checksum").asString(null));
            List<JsonNode> versions = store.rows("""
                    select row_to_json(t)::text from report_definition_version t
                    where t.report_definition_id = ? order by t.definition_version desc""", id);
            Optional<JsonNode> same = versions.stream()
                    .filter(v -> report.versionChecksum().equals(v.path("pack_checksum").asString(null))).findFirst();
            String versionState = same.map(v -> v.path("status").asString()).orElse(null);
            boolean rejected = "REJECTED".equals(versionState);
            boolean needsVersion = versionState == null;
            if (!dryRun) {
                if (defChanged || !pack.version().equals(def.path("pack_version").asString(null))) {
                    updateDefinition(pack, report, def, defChanged);
                }
                if (needsVersion) {
                    withdrawOlderDrafts(pack, versions);
                    JsonNode active = versions.stream().filter(v -> "ACTIVE".equals(v.path("status").asString()))
                            .findFirst().orElse(null);
                    DefinitionService.Added added = addVersion(pack, report, id, active);
                    pending.add(pendingEntry(report, added));
                    if (added.approvalRequestId() != null) {
                        requestIds.add(added.approvalRequestId().toString());
                    }
                } else if ("DRAFT".equals(versionState)) {
                    ObjectNode p = pending.addObject().put("code", report.code())
                            .put("report_definition_version_id", same.get().get("id").asString())
                            .put("definition_version", same.get().get("definition_version").asInt());
                    p.put("status", "DRAFT");
                }
            }
            if (rejected) {
                skipped.addObject().put("code", report.code()).put("reason", "version_rejected")
                        .put("message", "A checker rejected this pack version (v" + same.get().get("definition_version").asInt()
                                + "); it is not re-submitted. Correct and add a version manually, or wait for the next pack version");
            }
            if (defChanged || needsVersion) {
                ObjectNode u = updated.addObject().put("code", report.code());
                u.put("definition_changed", defChanged);
                u.put("new_version", needsVersion);
            } else if (!rejected) {
                unchanged.add(report.code());
            }
        }
        List<String> packCodes = pack.reports().stream().map(RegulatoryPack.Report::code).toList();
        for (JsonNode d : byCode.values()) {
            if (pack.code().equals(d.path("pack_code").asString(null)) && !packCodes.contains(d.get("code").asString())
                    && !"DEPRECATED".equals(d.path("status").asString())) {
                if (!dryRun) {
                    definitions.deprecate(UUID.fromString(d.get("id").asString()),
                            "Retired by regulatory pack " + pack.code() + " " + pack.version());
                }
                retired.add(d.get("code").asString());
            }
        }
        out.put("report_count", pack.reports().size());
        out.put("created_count", created.size());
        out.put("updated_count", updated.size());
        out.put("unchanged_count", unchanged.size());
        out.put("skipped_count", skipped.size());
        out.put("retired_count", retired.size());
        out.put("awaiting_publication_count", pending.size());
        out.put("notice", pack.notice());
        if (!dryRun) {
            recordInstallation(pack, legalEntityId, out);
        }
        return out;
    }

    private Object[] params(RegulatoryPack pack) {
        Object[] p = new Object[pack.reports().size() + 1];
        p[0] = pack.code();
        for (int i = 0; i < pack.reports().size(); i++) {
            p[i + 1] = pack.reports().get(i).code();
        }
        return p;
    }

    private UUID createDefinition(RegulatoryPack pack, RegulatoryPack.Report report) {
        ObjectNode v = report.definition().deepCopy();
        definitions.checkDefinition(v, null);
        v.put("status", "DRAFT");
        v.put("pack_code", pack.code());
        v.put("pack_version", pack.version());
        v.put("pack_checksum", report.definitionChecksum());
        JsonNode row = json.readTree(crud.insert("report_definition", v));
        return UUID.fromString(row.get("id").asString());
    }

    private void updateDefinition(RegulatoryPack pack, RegulatoryPack.Report report, JsonNode current, boolean managed) {
        ObjectNode changes = json.createObjectNode();
        if (managed) {
            RegulatoryPack.MANAGED.forEach(f -> changes.set(f, report.definition().get(f)));
            definitions.checkDefinition(changes, null);
            changes.put("pack_checksum", report.definitionChecksum());
        }
        changes.put("pack_version", pack.version());
        crud.updateRow("report_definition", Map.of("id", UUID.fromString(current.get("id").asString())), changes,
                current.get("row_version").asInt()).orElseThrow(() -> ApiException.conflict("version_conflict",
                "Report definition " + report.code() + " changed concurrently; retry the installation"));
    }

    private DefinitionService.Added addVersion(RegulatoryPack pack, RegulatoryPack.Report report, UUID definitionId,
                                               JsonNode active) {
        ObjectNode req = report.version().deepCopy();
        LocalDate from = LocalDate.parse(req.path("effective_from").asString());
        if (active != null) {
            LocalDate activeFrom = LocalDate.parse(active.get("effective_from").asString());
            if (!from.isAfter(activeFrom)) {
                LocalDate today = store.scalar("select current_date", LocalDate.class).orElseThrow();
                from = today.isAfter(activeFrom) ? today : activeFrom.plusDays(1);
                req.put("effective_from", from.toString());
            }
        }
        String note = req.path("change_note").asString("");
        req.put("change_note", ("Regulatory pack " + pack.code() + " " + pack.version() + (note.isBlank() ? "" : ": " + note)));
        ObjectNode packFields = json.createObjectNode();
        packFields.put("pack_version", pack.version());
        packFields.put("pack_checksum", report.versionChecksum());
        return definitions.addVersion(definitionId, req, packFields);
    }

    /** Older pack versions still waiting for publication are withdrawn (recorded as rejected) for the new one. */
    private void withdrawOlderDrafts(RegulatoryPack pack, List<JsonNode> versions) {
        for (JsonNode v : versions) {
            if ("DRAFT".equals(v.path("status").asString()) && v.hasNonNull("pack_checksum")) {
                UUID id = UUID.fromString(v.get("id").asString());
                store.update("""
                        update report_definition_version set status = 'REJECTED', rejected_by = ?, rejected_at = ?,
                        rejection_reason = ?, updated_by = ?, row_version = row_version + 1 where id = ? and status = 'DRAFT'""",
                        TenantContext.principal(), Clocks.now(), "Withdrawn: superseded by regulatory pack " + pack.code()
                                + " " + pack.version(), TenantContext.principal(), id);
                approvals.cancelOpen(DefinitionService.APPROVAL_RESOURCE, id, null, "superseded by pack " + pack.version());
            }
        }
    }

    private ObjectNode pendingEntry(RegulatoryPack.Report report, DefinitionService.Added added) {
        JsonNode row = json.readTree(added.row());
        ObjectNode p = json.createObjectNode();
        p.put("code", report.code());
        p.put("report_definition_version_id", row.get("id").asString());
        p.put("definition_version", row.get("definition_version").asInt());
        p.put("status", "DRAFT");
        if (added.approvalRequestId() != null) {
            p.put("approval_request_id", added.approvalRequestId().toString());
        }
        return p;
    }

    private void recordInstallation(RegulatoryPack pack, UUID legalEntityId, ObjectNode result) {
        Optional<JsonNode> existing = store.row("select row_to_json(t)::text from regulatory_pack_installation t "
                + "where t.pack_code = ? for update", pack.code());
        ObjectNode v = json.createObjectNode();
        v.put("pack_version", pack.version());
        v.put("country", pack.country());
        v.put("regulator", pack.regulator());
        v.put("report_count", pack.reports().size());
        v.put("pending_version_count", result.path("awaiting_publication").size());
        v.put("upgraded_by", TenantContext.principal());
        v.put("upgraded_at", Clocks.now().toString());
        v.set("last_result", result.deepCopy());
        String row;
        if (existing.isEmpty()) {
            v.put("pack_code", pack.code());
            if (legalEntityId != null) {
                v.put("legal_entity_id", legalEntityId.toString());
            }
            v.put("installed_by", TenantContext.principal());
            v.put("installed_at", Clocks.now().toString());
            row = crud.insert("regulatory_pack_installation", v);
        } else {
            if (legalEntityId != null) {
                v.put("legal_entity_id", legalEntityId.toString());
            }
            JsonNode cur = existing.get();
            row = crud.updateRow("regulatory_pack_installation", Map.of("id", UUID.fromString(cur.get("id").asString())),
                    v, cur.get("row_version").asInt()).orElseThrow(() -> ApiException.conflict("version_conflict",
                    "Pack installation changed concurrently; retry"));
        }
        JsonNode n = json.readTree(row);
        result.put("installation_id", n.get("id").asString());
        outbox.publish("reporting.regulatory_pack.installed", "regulatory_pack_installation",
                UUID.fromString(n.get("id").asString()), n);
    }
}
