package io.meridian.productcatalog.application;

import io.meridian.common.error.ApiException;
import io.meridian.common.error.ApiException.FieldError;
import io.meridian.productcatalog.domain.ConfigValidator;
import io.meridian.productcatalog.infrastructure.CatalogStore;
import io.meridian.web.crud.CrudEngine;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Create/update/delete of version content rows (parameters, interest rates and tiers, fees with tiers and
 * waivers, limits, eligibility rules, disclosures). Content can only change while its version is DRAFT:
 * submitted, approved and published versions are immutable (409 {@code version_not_editable}). The version row
 * is share-locked so an edit cannot race a submission.
 */
@Service
public class VersionContentService {
    private final CatalogStore store;
    private final CrudEngine crud;

    public VersionContentService(CatalogStore store, CrudEngine crud) {
        this.store = store;
        this.crud = crud;
    }

    /** Parent link of each content table: (column, parent table) - null parent table means product_version. */
    private record Parent(String column, String table) {}

    private static Parent parentOf(String table) {
        return switch (table) {
            case "interest_rate_tier" -> new Parent("interest_rate_config_id", "interest_rate_config");
            case "fee_tier", "fee_waiver_rule" -> new Parent("fee_definition_id", "fee_definition");
            default -> new Parent("product_version_id", null);
        };
    }

    @Transactional
    public String create(String table, Object body) {
        ObjectNode values = crud.toColumns(table, body);
        Parent parent = parentOf(table);
        if (!values.hasNonNull(parent.column())) {
            throw ApiException.unprocessable("parent_required", parent.column() + " is required");
        }
        Edit edit = guard(table, values);
        validate(table, CatalogStore.toMap(values), edit.productCurrency());
        return crud.insert(table, values);
    }

    @Transactional
    public String update(String table, UUID id, Object body, Integer expectedVersion) {
        JsonNode current = store.require(table, id);
        ObjectNode values = crud.toColumns(table, body);
        Parent parent = parentOf(table);
        if (values.hasNonNull(parent.column())
                && !values.get(parent.column()).asString().equals(current.get(parent.column()).asString())) {
            throw ApiException.unprocessable("immutable_field", parent.column() + " cannot be changed; create a new row instead");
        }
        values.remove(parent.column());
        Edit edit = guard(table, current);
        ObjectNode merged = (ObjectNode) current.deepCopy();
        merged.setAll(values);
        validate(table, CatalogStore.toMap(merged), edit.productCurrency());
        return crud.updateRow(table, Map.of("id", id), values, expectedVersion)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match does not match the current version; reload and retry"));
    }

    @Transactional
    public void delete(String table, UUID id) {
        JsonNode current = store.require(table, id);
        guard(table, current);
        switch (table) {
            case "interest_rate_config" -> store.deleteChildren("interest_rate_tier", "interest_rate_config_id", id);
            case "fee_definition" -> {
                store.deleteChildren("fee_tier", "fee_definition_id", id);
                store.deleteChildren("fee_waiver_rule", "fee_definition_id", id);
            }
            default -> { }
        }
        store.delete(table, id);
    }

    record Edit(JsonNode version, String productCurrency) {}

    /** Resolves the owning version, share-locks it and requires DRAFT. */
    Edit guard(String table, JsonNode row) {
        Parent parent = parentOf(table);
        UUID versionId;
        if (parent.table() == null) {
            versionId = UUID.fromString(row.get("product_version_id").asString());
        } else {
            UUID parentId = UUID.fromString(row.get(parent.column()).asString());
            JsonNode p = store.find(parent.table(), parentId).orElseThrow(() -> ApiException.unprocessable(
                    "parent_not_found", parent.table().replace('_', ' ') + " " + parentId + " does not exist"));
            versionId = UUID.fromString(p.get("product_version_id").asString());
        }
        JsonNode version = store.find("product_version", versionId).isPresent()
                ? store.lockShared("product_version", versionId)
                : null;
        if (version == null) {
            throw ApiException.unprocessable("product_version_not_found", "product version " + versionId + " does not exist");
        }
        requireEditable(version);
        JsonNode product = store.require("product", UUID.fromString(version.get("product_id").asString()));
        return new Edit(version, product.get("currency").asString().trim());
    }

    static void requireEditable(JsonNode version) {
        String status = version.get("status").asString();
        if (!"DRAFT".equals(status)) {
            throw ApiException.conflict("version_not_editable", "Product version " + version.get("version_no").asInt()
                    + " is " + status + "; only DRAFT versions can be changed (start a new draft)");
        }
    }

    private static void validate(String table, Map<String, Object> row, String currency) {
        List<FieldError> errors = switch (table) {
            case "product_parameter" -> ConfigValidator.parameter(row, "");
            case "interest_rate_config" -> ConfigValidator.rateConfig(row, currency, "");
            case "interest_rate_tier" -> ConfigValidator.rateTier(row, "");
            case "fee_definition" -> ConfigValidator.fee(row, currency, "");
            case "fee_tier" -> ConfigValidator.feeTier(row, "");
            case "fee_waiver_rule" -> ConfigValidator.waiver(row, "");
            case "product_limit_template" -> ConfigValidator.limit(row, currency, "");
            case "eligibility_rule" -> ConfigValidator.eligibility(row, "");
            default -> List.of();
        };
        ConfigValidator.throwIfAny("invalid_configuration", "The " + table.replace('_', ' ') + " is invalid", errors);
    }
}
