package io.meridian.config.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.id.Ids;
import io.meridian.config.domain.SemVer;
import io.meridian.config.domain.TaxCalculator;
import io.meridian.config.infrastructure.ConfigStore;
import io.meridian.events.OutboxWriter;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Installs released country packs for a tenant's legal entity, upgrades them to newer versions of the same
 * country and uninstalls them. Tax rules shipped in {@code contents.tax_rules} are seeded as tenant rules linked to
 * the pack, replaced on upgrade and expired on uninstall.
 */
@Service
public class CountryPackService {
    private final CrudEngine crud;
    private final ConfigStore store;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public CountryPackService(CrudEngine crud, ConfigStore store, OutboxWriter outbox, JsonMapper json) {
        this.crud = crud;
        this.store = store;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String install(UUID packId, UUID legalEntityId, JsonNode overrides) {
        if (legalEntityId == null) {
            throw ApiException.badRequest("validation_failed", "legal_entity_id is required");
        }
        JsonNode pack = releasedPack(packId);
        store.lockLegalEntity(legalEntityId);
        if (!store.liveInstallsForCountry(legalEntityId, pack.get("country").asString()).isEmpty()) {
            throw ApiException.conflict("already_installed", "A " + pack.get("country").asString()
                    + " country pack is already installed for legal entity " + legalEntityId + "; upgrade it instead");
        }
        // a previously uninstalled install of the same pack is replaced (unique per legal entity + pack)
        store.installOf(legalEntityId, packId).ifPresent(r -> store.deleteInstall(UUID.fromString(json.readTree(r).get("id").asString())));
        ObjectNode values = json.createObjectNode();
        values.put("id", Ids.newId().toString());
        values.put("country_pack_id", packId.toString());
        values.put("legal_entity_id", legalEntityId.toString());
        values.put("status", "INSTALLED");
        values.put("installed_at", OffsetDateTime.now().toString());
        if (overrides != null && !overrides.isNull()) {
            if (!overrides.isObject()) {
                throw ApiException.unprocessable("invalid_overrides", "overrides must be a JSON object");
            }
            values.set("overrides", overrides);
        }
        String row = crud.insert("tenant_country_pack", values);
        seedTaxRules(pack, legalEntityId);
        outbox.publish("platform.country_pack.installed", "tenant_country_pack",
                UUID.fromString(values.get("id").asString()), json.readTree(row));
        return row;
    }

    @Transactional
    public String upgrade(UUID installId, UUID targetPackId) {
        if (targetPackId == null) {
            throw ApiException.badRequest("validation_failed", "country_pack_id is required");
        }
        JsonNode install = json.readTree(store.install(installId, true)
                .orElseThrow(() -> ApiException.notFound("installed country pack", installId)));
        String status = install.get("status").asString();
        if (!"INSTALLED".equals(status) && !"UPGRADE_AVAILABLE".equals(status)) {
            throw ApiException.conflict("invalid_transition", "Only INSTALLED packs can be upgraded; this one is " + status);
        }
        JsonNode current = json.readTree(store.pack(UUID.fromString(install.get("country_pack_id").asString())).orElseThrow());
        JsonNode target = releasedPack(targetPackId);
        if (!current.get("country").asString().equals(target.get("country").asString())) {
            throw ApiException.unprocessable("country_mismatch", "Target pack is for " + target.get("country").asString()
                    + " but the installed pack is for " + current.get("country").asString());
        }
        if (SemVer.parse(target.get("version").asString()).compareTo(SemVer.parse(current.get("version").asString())) <= 0) {
            throw ApiException.unprocessable("not_an_upgrade", "Target version " + target.get("version").asString()
                    + " is not newer than installed " + current.get("version").asString());
        }
        UUID legalEntityId = UUID.fromString(install.get("legal_entity_id").asString());
        store.installOf(legalEntityId, targetPackId).ifPresent(r -> store.deleteInstall(UUID.fromString(json.readTree(r).get("id").asString())));
        store.expirePackTaxRules(UUID.fromString(current.get("id").asString()), legalEntityId);
        ObjectNode upd = json.createObjectNode();
        upd.put("country_pack_id", targetPackId.toString());
        upd.put("status", "INSTALLED");
        upd.put("installed_at", OffsetDateTime.now().toString());
        String row = crud.updateRow("tenant_country_pack", Map.of("id", installId), upd, null).orElseThrow();
        seedTaxRules(target, legalEntityId);
        outbox.publish("platform.country_pack.installed", "tenant_country_pack", installId, json.readTree(row));
        return row;
    }

    @Transactional
    public String uninstall(UUID installId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("validation_failed", "reason is required");
        }
        JsonNode install = json.readTree(store.install(installId, true)
                .orElseThrow(() -> ApiException.notFound("installed country pack", installId)));
        if ("UNINSTALLED".equals(install.get("status").asString())) {
            throw ApiException.conflict("invalid_transition", "Country pack is already uninstalled");
        }
        store.expirePackTaxRules(UUID.fromString(install.get("country_pack_id").asString()),
                UUID.fromString(install.get("legal_entity_id").asString()));
        ObjectNode upd = json.createObjectNode();
        upd.put("status", "UNINSTALLED");
        return crud.updateRow("tenant_country_pack", Map.of("id", installId), upd, null).orElseThrow();
    }

    private JsonNode releasedPack(UUID packId) {
        JsonNode pack = json.readTree(store.pack(packId).orElseThrow(() -> ApiException.notFound("country pack", packId)));
        if (!"RELEASED".equals(pack.get("status").asString())) {
            throw ApiException.unprocessable("pack_not_released", "Country pack " + pack.get("code").asString()
                    + " is " + pack.get("status").asString() + "; only RELEASED packs can be installed");
        }
        return pack;
    }

    private void seedTaxRules(JsonNode pack, UUID legalEntityId) {
        JsonNode rules = pack.path("contents").path("tax_rules");
        if (!rules.isArray()) {
            return;
        }
        for (JsonNode r : rules) {
            ObjectNode v = json.createObjectNode();
            v.put("country_pack_id", pack.get("id").asString());
            v.put("legal_entity_id", legalEntityId.toString());
            v.put("country", pack.get("country").asString());
            for (String f : new String[] {"tax_type", "name", "rate", "applies_to", "customer_segment",
                    "threshold_amount", "currency", "tax_gl_code", "valid_from", "valid_to"}) {
                if (r.hasNonNull(f)) {
                    v.put(f, r.get(f).asString());
                }
            }
            if (!v.has("valid_from")) {
                v.put("valid_from", LocalDate.now().toString());
            }
            TaxCalculator.requireValidRule(v.has("rate") ? new BigDecimal(v.get("rate").asString()) : null,
                    LocalDate.parse(v.get("valid_from").asString()),
                    v.has("valid_to") ? LocalDate.parse(v.get("valid_to").asString()) : null,
                    v.has("threshold_amount") ? new BigDecimal(v.get("threshold_amount").asString()) : null,
                    v.has("currency") ? v.get("currency").asString() : null);
            crud.insert("tax_rate_rule", v);
        }
    }
}
