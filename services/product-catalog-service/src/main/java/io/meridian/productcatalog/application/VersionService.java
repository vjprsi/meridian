package io.meridian.productcatalog.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.events.OutboxWriter;
import io.meridian.productcatalog.domain.ConfigValidator;
import io.meridian.productcatalog.domain.Lifecycles;
import io.meridian.productcatalog.domain.definition.Canonical;
import io.meridian.productcatalog.domain.definition.DefinitionDiff;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Product versions: drafting, maker-checker approval (approver must differ from submitter), publication (exactly
 * one PUBLISHED version per product; the previous one becomes SUPERSEDED), new drafts, diffs and account
 * migration requests. Published versions are immutable.
 */
@Service
public class VersionService {
    static final String VERSION = "product_version";
    static final Set<String> MIGRATION_POLICIES = Set.of("PIN", "MIGRATE_ON_RENEWAL", "FORCE_MIGRATE");
    static final String MIGRATION_EVENT = "product.product_version.migration_requested";

    private final CatalogStore store;
    private final CrudEngine crud;
    private final DefinitionService definitions;
    private final ProductService products;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public VersionService(CatalogStore store, CrudEngine crud, DefinitionService definitions, ProductService products,
                          OutboxWriter outbox, JsonMapper json) {
        this.store = store;
        this.crud = crud;
        this.definitions = definitions;
        this.products = products;
        this.outbox = outbox;
        this.json = json;
    }

    /** New DRAFT version; sections in {@code definition} are materialised as rows. One in-flight version per product. */
    @Transactional
    public String create(Object body) {
        ObjectNode values = crud.toColumns(VERSION, body);
        UUID productId = UUID.fromString(values.get("product_id").asString());
        JsonNode product = lockOpenProduct(productId);
        requireNoInFlight(productId);
        policy(values);
        Map<String, Object> def = CatalogStore.toMap(values.get("definition"));
        values.put("version_no", store.nextVersionNo(productId));
        values.put("status", "DRAFT");
        values.set("definition", store.toNumericNode(definitions.settingsOnly(def)));
        window(values);
        JsonNode row = store.insert(VERSION, values);
        definitions.materialize(ProductService.id(row), def, product.get("currency").asString().trim());
        return row.toString();
    }

    @Transactional
    public String update(UUID id, Object body, Integer expectedVersion) {
        JsonNode current = store.lock(VERSION, id);
        VersionContentService.requireEditable(current);
        ObjectNode values = crud.toColumns(VERSION, body);
        for (String f : List.of("product_id", "version_no")) {
            if (values.hasNonNull(f) && !values.get(f).asString().equals(current.get(f).asString())) {
                throw ApiException.unprocessable("immutable_field", f + " cannot be changed");
            }
            values.remove(f);
        }
        if (values.has("definition")) {
            Map<String, Object> def = CatalogStore.toMap(values.get("definition"));
            if (DefinitionDiff.SECTIONS.keySet().stream().anyMatch(def::containsKey)) {
                throw ApiException.unprocessable("definition_sections_managed",
                        "parameters, interest_rates, fees, limits, eligibility_rules and scripts are edited through their own resources");
            }
            values.set("definition", store.toNumericNode(def));
        }
        policy(values);
        ObjectNode merged = (ObjectNode) current.deepCopy();
        merged.setAll(values);
        window(merged);
        return crud.updateRow(VERSION, Map.of("id", id), values, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match does not match the current version; reload and retry"));
    }

    /** Maker: freezes the resolved definition (snapshot + checksum) and requests approval. */
    @Transactional
    public String submit(UUID id, String comment) {
        JsonNode v = store.lock(VERSION, id);
        String to = Lifecycles.VERSION.apply(v.get("status").asString(), "submit");
        JsonNode product = store.require("product", UUID.fromString(v.get("product_id").asString()));
        if ("RETIRED".equals(product.get("status").asString())) {
            throw ApiException.conflict("product_retired", "The product is retired");
        }
        Map<String, Object> def = definitions.resolved(v);
        ConfigValidator.throwIfAny("version_invalid", "The version cannot be submitted: fix the listed problems",
                ConfigValidator.version(def, product.get("currency").asString().trim()));
        ObjectNode values = json.createObjectNode();
        values.put("status", to);
        values.set("definition", store.toNumericNode(def));
        values.put("checksum", definitions.checksum(def));
        values.put("submitted_by", TenantContext.principal());
        values.put("submitted_at", OffsetDateTime.now().toString());
        values.putNull("approved_by");
        values.putNull("approved_at");
        values.putNull("rejected_by");
        values.putNull("rejected_at");
        values.putNull("rejection_reason");
        if (comment != null && !comment.isBlank()) {
            values.put("change_summary", comment);
        }
        JsonNode row = store.update(VERSION, id, values);
        outbox.publish("product.product_version.submitted", VERSION, id, row);
        return row.toString();
    }

    /** Checker: four-eyes, integrity check of the frozen definition. */
    @Transactional
    public String approve(UUID id, String comment) {
        JsonNode v = store.lock(VERSION, id);
        String to = Lifecycles.VERSION.apply(v.get("status").asString(), "approve");
        Lifecycles.requireFourEyes(v.path("submitted_by").isNull() ? null : v.path("submitted_by").asString(null),
                TenantContext.principal(), "Product version " + v.get("version_no").asInt());
        requireIntact(v);
        ObjectNode values = json.createObjectNode();
        values.put("status", to);
        values.put("approved_by", TenantContext.principal());
        values.put("approved_at", OffsetDateTime.now().toString());
        JsonNode row = store.update(VERSION, id, values);
        outbox.publish("product.product_version.approved", VERSION, id, withNote(row, "comment", comment));
        return row.toString();
    }

    @Transactional
    public String reject(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("reason_required", "A rejection reason is required");
        }
        JsonNode v = store.lock(VERSION, id);
        String to = Lifecycles.VERSION.apply(v.get("status").asString(), "reject");
        ObjectNode values = json.createObjectNode();
        values.put("status", to);
        values.put("rejected_by", TenantContext.principal());
        values.put("rejected_at", OffsetDateTime.now().toString());
        values.put("rejection_reason", reason);
        JsonNode row = store.update(VERSION, id, values);
        outbox.publish("product.product_version.rejected", VERSION, id, row);
        return row.toString();
    }

    /**
     * Publishes an APPROVED version: it becomes the single PUBLISHED version of the product (the previous one is
     * SUPERSEDED), the product points at it and a DRAFT product is activated.
     */
    @Transactional
    public String publish(UUID id, OffsetDateTime effectiveFrom) {
        JsonNode peek = store.require(VERSION, id);
        UUID productId = UUID.fromString(peek.get("product_id").asString());
        JsonNode product = store.lock("product", productId);
        JsonNode v = store.lock(VERSION, id);
        String to = Lifecycles.VERSION.apply(v.get("status").asString(), "publish");
        if ("RETIRED".equals(product.get("status").asString())) {
            throw ApiException.conflict("product_retired", "Cannot publish a version of a retired product");
        }
        requireIntact(v);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime from = effectiveFrom != null ? effectiveFrom : now;
        JsonNode previous = null;
        for (JsonNode other : store.versionsOfProduct(productId)) {
            if ("PUBLISHED".equals(other.get("status").asString()) && !other.get("id").asString().equals(id.toString())) {
                ObjectNode sup = json.createObjectNode();
                sup.put("status", Lifecycles.VERSION.apply("PUBLISHED", "supersede"));
                sup.put("effective_to", from.toString());
                store.update(VERSION, ProductService.id(other), sup);
                previous = other;
            }
        }
        ObjectNode values = json.createObjectNode();
        values.put("status", to);
        values.put("published_at", now.toString());
        values.put("effective_from", from.toString());
        JsonNode row = store.update(VERSION, id, values);

        ObjectNode pv = json.createObjectNode();
        pv.put("current_version_id", id.toString());
        String status = product.get("status").asString();
        String newStatus = status;
        if ("DRAFT".equals(status)) {
            newStatus = Lifecycles.PRODUCT.apply(status, "activate");
            pv.put("status", newStatus);
            pv.put("is_sellable", true);
        }
        JsonNode updatedProduct = store.update("product", productId, pv);
        outbox.publish("product.product_version.published", VERSION, id, row);
        if (!newStatus.equals(status)) {
            products.statusChanged(updatedProduct, status, newStatus, "first version published");
        }
        if (previous != null) {
            rateChanges(previous, row);
        }
        return row.toString();
    }

    /** Starts a new DRAFT from this version's resolved definition (one in-flight version per product). */
    @Transactional
    public String newDraft(UUID id) {
        JsonNode source = store.require(VERSION, id);
        UUID productId = UUID.fromString(source.get("product_id").asString());
        JsonNode product = lockOpenProduct(productId);
        requireNoInFlight(productId);
        Map<String, Object> def = definitions.resolved(source);
        ObjectNode values = json.createObjectNode();
        values.put("product_id", productId.toString());
        values.put("version_no", store.nextVersionNo(productId));
        values.put("status", "DRAFT");
        values.set("definition", store.toNumericNode(definitions.settingsOnly(def)));
        values.put("migration_policy", source.get("migration_policy").asString());
        values.put("change_summary", "Draft from version " + source.get("version_no").asInt());
        JsonNode row = store.insert(VERSION, values);
        definitions.materialize(ProductService.id(row), def, product.get("currency").asString().trim());
        return row.toString();
    }

    /** Structured differences from {@code against} to this version. */
    public Map<String, Object> diff(UUID id, UUID against) {
        if (against == null) {
            throw ApiException.badRequest("against_required", "Query parameter ?against=<version id> is required");
        }
        JsonNode to = store.require(VERSION, id);
        JsonNode from = store.require(VERSION, against);
        Map<String, Object> d = DefinitionDiff.diff(definitions.resolved(from), definitions.resolved(to));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version_id", id.toString());
        out.put("version_no", to.get("version_no").asInt());
        out.put("against_version_id", against.toString());
        out.put("against_version_no", from.get("version_no").asInt());
        out.put("same_product", to.get("product_id").asString().equals(from.get("product_id").asString()));
        out.putAll(d);
        return out;
    }

    /**
     * Records a request to move accounts pinned to {@code fromVersionId} onto this (published) version. The
     * migration itself is performed by the account-owning services consuming the event.
     */
    @Transactional
    public String migrateAccounts(UUID id, UUID fromVersionId, LocalDate effectiveDate) {
        if (fromVersionId == null || effectiveDate == null) {
            throw ApiException.unprocessable("invalid_request", "from_version_id and effective_date are required");
        }
        JsonNode target = store.lock(VERSION, id);
        if (!"PUBLISHED".equals(target.get("status").asString())) {
            throw ApiException.conflict("version_not_published", "Accounts can only migrate to the PUBLISHED version");
        }
        if (fromVersionId.equals(id)) {
            throw ApiException.unprocessable("same_version", "from_version_id must differ from the target version");
        }
        JsonNode from = store.find(VERSION, fromVersionId).orElseThrow(() ->
                ApiException.unprocessable("from_version_not_found", "product version " + fromVersionId + " does not exist"));
        if (!from.get("product_id").asString().equals(target.get("product_id").asString())) {
            throw ApiException.unprocessable("version_product_mismatch", "Both versions must belong to the same product");
        }
        if (from.get("version_no").asInt() >= target.get("version_no").asInt()) {
            throw ApiException.unprocessable("not_an_upgrade", "Accounts can only migrate to a newer version");
        }
        if (!Lifecycles.VERSION_LIVE_OR_PAST.contains(from.get("status").asString())
                && !"RETIRED".equals(from.get("status").asString())) {
            throw ApiException.conflict("invalid_source_version", "Version " + from.get("version_no").asInt()
                    + " was never published, so no accounts are pinned to it");
        }
        if (effectiveDate.isBefore(LocalDate.now())) {
            throw ApiException.unprocessable("effective_date_in_past", "effective_date cannot be in the past");
        }
        ObjectNode payload = (ObjectNode) target.deepCopy();
        ObjectNode req = payload.putObject("migration_request");
        req.put("from_version_id", fromVersionId.toString());
        req.put("from_version_no", from.get("version_no").asInt());
        req.put("to_version_id", id.toString());
        req.put("to_version_no", target.get("version_no").asInt());
        req.put("effective_date", effectiveDate.toString());
        req.put("migration_policy", target.get("migration_policy").asString());
        req.put("requested_by", TenantContext.principal());
        req.put("requested_at", OffsetDateTime.now().toString());
        outbox.publish(MIGRATION_EVENT, VERSION, id, payload);
        return target.toString();
    }

    // ------------------------------------------------------------------ helpers

    private void rateChanges(JsonNode previous, JsonNode published) {
        Map<String, Object> before = definitions.resolved(previous);
        Map<String, Object> after = definitions.resolved(published);
        Map<String, Map<String, Object>> old = byKind(before.get("interest_rates"));
        List<JsonNode> configs = store.children("interest_rate_config", "product_version_id",
                ProductService.id(published), "rate_kind, effective_from");
        Map<String, Map<String, Object>> now = byKind(after.get("interest_rates"));
        for (JsonNode cfg : configs) {
            String kind = cfg.get("rate_kind").asString();
            Map<String, Object> was = old.get(kind);
            if (was == null || !Canonical.of(was).equals(Canonical.of(now.get(kind)))) {
                ObjectNode payload = (ObjectNode) cfg.deepCopy();
                if (was == null) {
                    payload.putNull("previous");
                } else {
                    payload.set("previous", store.toNode(was));
                }
                payload.set("current", store.toNode(now.get(kind)));
                outbox.publish("product.interest_rate_config.changed", "interest_rate_config", ProductService.id(cfg), payload);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> byKind(Object section) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        if (section instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> c) {
                    m.putIfAbsent(String.valueOf(c.get("rate_kind")), (Map<String, Object>) c);
                }
            }
        }
        return m;
    }

    private void requireIntact(JsonNode v) {
        if (!definitions.intact(v)) {
            throw ApiException.conflict("checksum_mismatch",
                    "The version content no longer matches its submitted checksum; reject it and start a new draft");
        }
    }

    private JsonNode lockOpenProduct(UUID productId) {
        JsonNode product = store.find("product", productId).isPresent() ? store.lock("product", productId) : null;
        if (product == null) {
            throw ApiException.unprocessable("product_not_found", "product " + productId + " does not exist");
        }
        if ("RETIRED".equals(product.get("status").asString())) {
            throw ApiException.conflict("product_retired", "Product " + productId + " is retired");
        }
        return product;
    }

    private void requireNoInFlight(UUID productId) {
        for (JsonNode v : store.versionsOfProduct(productId)) {
            if (Lifecycles.VERSION_IN_FLIGHT.contains(v.get("status").asString())) {
                throw ApiException.conflict("version_in_progress", "Version " + v.get("version_no").asInt() + " is still "
                        + v.get("status").asString() + "; publish or reject it before starting another");
            }
        }
    }

    private static void policy(JsonNode values) {
        if (values.hasNonNull("migration_policy") && !MIGRATION_POLICIES.contains(values.get("migration_policy").asString())) {
            throw ApiException.unprocessable("invalid_migration_policy", "migration_policy must be one of " + MIGRATION_POLICIES);
        }
    }

    private static void window(JsonNode v) {
        if (v.hasNonNull("effective_from") && v.hasNonNull("effective_to")
                && !OffsetDateTime.parse(v.get("effective_to").asString())
                .isAfter(OffsetDateTime.parse(v.get("effective_from").asString()))) {
            throw ApiException.unprocessable("invalid_effective_window", "effective_to must be after effective_from");
        }
    }

    private static JsonNode withNote(JsonNode row, String field, String value) {
        if (value == null || value.isBlank()) {
            return row;
        }
        ObjectNode copy = (ObjectNode) row.deepCopy();
        copy.put(field, value);
        return copy;
    }
}
