package io.meridian.productcatalog.application;

import io.meridian.common.error.ApiException;
import io.meridian.events.OutboxWriter;
import io.meridian.productcatalog.domain.Lifecycles;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Catalogue entries: creation (plain, from template, clone), guarded edits, suspension and retirement. */
@Service
public class ProductService {
    static final String PRODUCT = "product";
    /** Columns copied when cloning a product. */
    static final List<String> CLONED = List.of("family", "segment", "template_id", "legal_entity_id", "currency",
            "short_description", "long_description", "available_channels", "available_countries", "owner_user_id",
            "metadata");

    private final CatalogStore store;
    private final CrudEngine crud;
    private final DefinitionService definitions;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public ProductService(CatalogStore store, CrudEngine crud, DefinitionService definitions, OutboxWriter outbox,
                          JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.definitions = definitions;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode values = crud.toColumns(PRODUCT, body);
        validateNew(values);
        if (values.hasNonNull("template_id")) {
            JsonNode tpl = activeTemplate(UUID.fromString(values.get("template_id").asString()));
            if (!tpl.get("family").asString().equals(values.get("family").asString())) {
                throw ApiException.unprocessable("family_mismatch", "The product family must match the template family "
                        + tpl.get("family").asString());
            }
        }
        JsonNode row = store.insert(PRODUCT, values);
        outbox.publish("product.product.created", PRODUCT, id(row), row);
        return row.toString();
    }

    @Transactional
    public String update(UUID id, Object body, Integer expectedVersion) {
        JsonNode current = store.lock(PRODUCT, id);
        String status = current.get("status").asString();
        if ("RETIRED".equals(status)) {
            throw ApiException.conflict("product_retired", "Product " + id + " is retired and can no longer change");
        }
        ObjectNode values = crud.toColumns(PRODUCT, body);
        String target = status;
        if (values.hasNonNull("status") && !values.get("status").asString().equals(status)) {
            String wanted = values.get("status").asString();
            // The contract has no activate/reactivate action: lifting a suspension is the one status change
            // allowed through PATCH; everything else goes through suspend/retire/publish.
            if (!("SUSPENDED".equals(status) && "ACTIVE".equals(wanted))) {
                throw ApiException.conflict("invalid_transition", "Status " + status + " -> " + wanted
                        + " is not allowed via PATCH; use the suspend/retire actions or publish a version");
            }
            target = Lifecycles.PRODUCT.apply(status, "reactivate");
        }
        boolean hasVersions = !store.versionsOfProduct(id).isEmpty();
        for (String f : List.of("currency", "family")) {
            if (values.hasNonNull(f) && !values.get(f).asString().trim().equals(current.get(f).asString().trim()) && hasVersions) {
                throw ApiException.conflict("immutable_field", f + " cannot change once the product has versions");
            }
        }
        if (values.hasNonNull("currency")) {
            currency(values.get("currency").asString());
        }
        boolean sellable = values.hasNonNull("is_sellable") ? values.get("is_sellable").asBoolean()
                : current.get("is_sellable").asBoolean();
        if (sellable && (!"ACTIVE".equals(target) || !current.hasNonNull("current_version_id"))) {
            throw ApiException.unprocessable("not_sellable", "Only ACTIVE products with a published version can be sellable");
        }
        String row = crud.updateRow(PRODUCT, Map.of("id", id), values, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match does not match the current version; reload and retry"));
        if (!target.equals(status)) {
            statusChanged(json.readTree(row), status, target, "reactivated");
        }
        return row;
    }

    public record FromTemplate(UUID templateId, String code, String name, String currency, UUID legalEntityId) {}

    /** Creates a DRAFT product and its version 1 from a template's default definition. */
    @Transactional
    public String fromTemplate(FromTemplate r) {
        JsonNode tpl = activeTemplate(r.templateId());
        ObjectNode values = json.createObjectNode();
        values.put("code", r.code());
        values.put("name", r.name());
        values.put("family", tpl.get("family").asString());
        values.put("template_id", r.templateId().toString());
        values.put("currency", r.currency());
        values.put("legal_entity_id", r.legalEntityId().toString());
        if (tpl.hasNonNull("description")) {
            values.put("long_description", tpl.get("description").asString());
        }
        validateNew(values);
        JsonNode product = store.insert(PRODUCT, values);
        Map<String, Object> def = CatalogStore.toMap(tpl.get("default_definition"));
        createFirstVersion(id(product), def, r.currency(), "Created from template " + tpl.get("code").asString());
        outbox.publish("product.product.created", PRODUCT, id(product), product);
        return product.toString();
    }

    /** Clones a product: new DRAFT product, version 1 seeded from the source's current (else latest) version. */
    @Transactional
    public String cloneProduct(UUID sourceId, String code, String name) {
        JsonNode src = store.require(PRODUCT, sourceId);
        ObjectNode values = json.createObjectNode();
        values.put("code", code);
        values.put("name", name);
        for (String c : CLONED) {
            if (src.hasNonNull(c)) {
                if (c.equals("currency")) {
                    values.put(c, src.get(c).asString().trim());
                } else {
                    values.set(c, src.get(c));
                }
            }
        }
        validateNew(values);
        JsonNode product = store.insert(PRODUCT, values);
        UUID newId = id(product);
        JsonNode sourceVersion = null;
        if (src.hasNonNull("current_version_id")) {
            sourceVersion = store.require("product_version", UUID.fromString(src.get("current_version_id").asString()));
        } else {
            List<JsonNode> versions = store.versionsOfProduct(sourceId);
            if (!versions.isEmpty()) {
                sourceVersion = versions.getLast();
            }
        }
        if (sourceVersion != null) {
            Map<String, Object> def = definitions.resolved(sourceVersion);
            createFirstVersion(newId, def, values.get("currency").asString(),
                    "Cloned from " + src.get("code").asString() + " v" + sourceVersion.get("version_no").asInt());
        }
        for (String table : List.of("product_availability", "relationship_pricing")) {
            for (JsonNode row : store.children(table, "product_id", sourceId, "id")) {
                Map<String, Object> m = DefinitionService.clean(row);
                m.put("product_id", newId.toString());
                store.insert(table, (ObjectNode) store.toNumericNode(m));
            }
        }
        outbox.publish("product.product.created", PRODUCT, newId, product);
        return product.toString();
    }

    @Transactional
    public String suspend(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "A suspension reason is required");
        }
        JsonNode p = store.lock(PRODUCT, id);
        String from = p.get("status").asString();
        String to = Lifecycles.PRODUCT.apply(from, "suspend");
        ObjectNode values = json.createObjectNode();
        values.put("status", to);
        values.put("is_sellable", false);
        JsonNode row = store.update(PRODUCT, id, values);
        statusChanged(row, from, to, reason);
        return row.toString();
    }

    @Transactional
    public String retire(UUID id, LocalDate retireDate, String reason) {
        if (retireDate == null) {
            throw ApiException.unprocessable("retire_date_required", "retire_date is required");
        }
        if (retireDate.isBefore(store.today())) {
            throw ApiException.unprocessable("retire_date_in_past", "retire_date cannot be in the past");
        }
        JsonNode p = store.lock(PRODUCT, id);
        String from = p.get("status").asString();
        String to = Lifecycles.PRODUCT.apply(from, "retire");
        ObjectNode values = json.createObjectNode();
        values.put("status", to);
        values.put("is_sellable", false);
        values.put("retire_date", retireDate.toString());
        JsonNode row = store.update(PRODUCT, id, values);
        // New sales stop; existing accounts stay on their pinned version.
        for (JsonNode v : store.versionsOfProduct(id)) {
            if ("PUBLISHED".equals(v.get("status").asString()) && !v.hasNonNull("effective_to")) {
                store.update("product_version", id(v), json.createObjectNode()
                        .put("effective_to", retireDate.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime().toString()));
            }
        }
        statusChanged(row, from, to, reason);
        return row.toString();
    }

    void statusChanged(JsonNode product, String from, String to, String reason) {
        ObjectNode payload = (ObjectNode) product.deepCopy();
        payload.put("previous_status", from);
        if (reason != null) {
            payload.put("reason", reason);
        }
        outbox.publish("product.product.status_changed", PRODUCT, id(product), payload);
    }

    private void createFirstVersion(UUID productId, Map<String, Object> def, String currency, String summary) {
        ObjectNode v = json.createObjectNode();
        v.put("product_id", productId.toString());
        v.put("version_no", 1);
        v.put("status", "DRAFT");
        v.set("definition", store.toNumericNode(definitions.settingsOnly(def)));
        v.put("change_summary", summary);
        JsonNode version = store.insert("product_version", v);
        definitions.materialize(id(version), def, currency);
    }

    private JsonNode activeTemplate(UUID templateId) {
        JsonNode tpl = store.find("product_template", templateId).orElseThrow(() ->
                ApiException.unprocessable("template_not_found", "product template " + templateId + " does not exist"));
        if (!tpl.get("is_active").asBoolean()) {
            throw ApiException.unprocessable("template_inactive", "Template " + tpl.get("code").asString() + " is not active");
        }
        return tpl;
    }

    private void validateNew(ObjectNode values) {
        if (values.hasNonNull("status") && !"DRAFT".equals(values.get("status").asString())) {
            throw ApiException.unprocessable("invalid_initial_status", "Products are created as DRAFT; publish a version to activate");
        }
        if (values.hasNonNull("is_sellable") && values.get("is_sellable").asBoolean()) {
            throw ApiException.unprocessable("not_sellable", "A new product cannot be sellable before a version is published");
        }
        values.put("status", "DRAFT");
        currency(values.path("currency").asString(""));
        if (store.byCode(PRODUCT, values.path("code").asString("")).isPresent()) {
            throw ApiException.conflict("duplicate_code", "A product with code " + values.get("code").asString() + " exists");
        }
    }

    static void currency(String code) {
        try {
            Currency.getInstance(code.trim());
        } catch (RuntimeException e) {
            throw ApiException.unprocessable("invalid_currency", "'" + code + "' is not an ISO 4217 currency");
        }
    }

    static UUID id(JsonNode row) {
        return UUID.fromString(row.get("id").asString());
    }
}
