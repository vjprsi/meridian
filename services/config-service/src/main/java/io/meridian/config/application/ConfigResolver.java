package io.meridian.config.application;

import io.meridian.common.error.ApiException;
import io.meridian.config.domain.JsonDiff;
import io.meridian.config.infrastructure.ConfigStore;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Effective configuration of a namespace. Precedence, highest first:
 * <ol>
 *   <li>{@code tenant} - the tenant's ACTIVE config entry for the key;</li>
 *   <li>{@code country_pack_override} - {@code tenant_country_pack.overrides.config.<namespace>.<key>} of an installed pack;</li>
 *   <li>{@code country_pack} - {@code country_pack.contents.config.<namespace>.<key>} of an installed pack;</li>
 *   <li>{@code default} - the {@code default} keyword of the key's value_schema (latest version of the key).</li>
 * </ol>
 * Installed packs can be narrowed with {@code legal_entity_id} and/or {@code country}; later installs win.
 */
@Service
public class ConfigResolver {
    private final ConfigStore store;
    private final JsonMapper json;

    public ConfigResolver(ConfigStore store, JsonMapper json) {
        this.store = store;
        this.json = json;
    }

    private record Resolved(JsonNode value, ObjectNode source) {}

    @Transactional(readOnly = true)
    public JsonNode resolve(String namespace, UUID legalEntityId, String country) {
        if (namespace == null || namespace.isBlank()) {
            throw ApiException.badRequest("namespace_required", "Query parameter namespace is required");
        }
        Map<String, Resolved> out = new TreeMap<>();
        // 4. schema defaults
        for (String r : store.latestPerKey(namespace)) {
            JsonNode e = json.readTree(r);
            JsonNode schema = e.get("value_schema");
            if (schema != null && schema.isObject() && schema.has("default")) {
                out.put(e.get("config_key").asString(), new Resolved(schema.get("default"), source("default")));
            }
        }
        // 3 + 2. installed country packs (contents, then tenant overrides)
        for (String r : store.installedPacks(legalEntityId, country)) {
            JsonNode both = json.readTree(r);
            JsonNode pack = both.get("pack");
            JsonNode install = both.get("install");
            apply(out, JsonDiff.objectAt(pack.get("contents"), "config", namespace), "country_pack", pack, install);
            apply(out, JsonDiff.objectAt(install.get("overrides"), "config", namespace), "country_pack_override", pack, install);
        }
        // 1. tenant ACTIVE entries
        for (String r : store.activeEntries(java.util.List.of(namespace))) {
            JsonNode e = json.readTree(r);
            ObjectNode src = source("tenant");
            src.put("config_entry_id", e.get("id").asString());
            src.put("version", e.get("version").asInt());
            out.put(e.get("config_key").asString(), new Resolved(e.get("value"), src));
        }
        ObjectNode result = json.createObjectNode();
        result.put("namespace", namespace);
        if (legalEntityId != null) {
            result.put("legal_entity_id", legalEntityId.toString());
        }
        if (country != null) {
            result.put("country", country);
        }
        result.put("resolved_at", OffsetDateTime.now().toString());
        ObjectNode values = result.putObject("values");
        ObjectNode sources = result.putObject("sources");
        out.forEach((k, v) -> {
            values.set(k, v.value());
            sources.set(k, v.source());
        });
        return result;
    }

    private void apply(Map<String, Resolved> out, JsonNode layer, String kind, JsonNode pack, JsonNode install) {
        if (layer == null) {
            return;
        }
        for (Map.Entry<String, JsonNode> e : layer.properties()) {
            ObjectNode src = source(kind);
            src.put("country_pack_code", pack.get("code").asString());
            src.put("country_pack_version", pack.get("version").asString());
            src.put("installed_country_pack_id", install.get("id").asString());
            out.put(e.getKey(), new Resolved(e.getValue(), src));
        }
    }

    private ObjectNode source(String kind) {
        ObjectNode s = json.createObjectNode();
        s.put("source", kind);
        return s;
    }
}
