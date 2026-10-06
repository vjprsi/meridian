package io.meridian.audit.application;

import io.meridian.audit.domain.ExportRules;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Validation of retention rules before they are stored by the generic CRUD path. */
@Service
public class RetentionPolicyService {
    private static final String TABLE = "audit_retention_policy";
    private final CrudEngine crud;
    private final JdbcClient jdbc;

    public RetentionPolicyService(CrudEngine crud, JdbcClient jdbc) {
        this.crud = crud;
        this.jdbc = jdbc;
    }

    public void validateCreate(Object body) {
        TenantContext.require();
        ObjectNode v = crud.toColumns(TABLE, body);
        check(v.path("resource_type").asString(null), v.path("retention_years").asInt(10),
                v.path("archive_after_days").asInt(365));
        String country = v.path("country").asString(null);
        if (country == null) {
            // NULL country is not covered by the unique constraint; one default rule per resource type.
            boolean exists = jdbc.sql("select exists(select 1 from audit_retention_policy where resource_type = ? and country is null)")
                    .param(v.path("resource_type").asString()).query(Boolean.class).single();
            if (exists) {
                throw ApiException.conflict("duplicate", "A retention policy for this resource type without country exists");
            }
        }
    }

    public void validateUpdate(UUID id, Object body) {
        TenantContext.require();
        JsonNode current = crud.findOne(TABLE, Map.of("id", id))
                .map(s -> crud.read(s, JsonNode.class))
                .orElseThrow(() -> ApiException.notFound("audit retention policy", id));
        ObjectNode v = crud.toColumns(TABLE, body);
        check(v.has("resource_type") ? v.get("resource_type").asString() : current.path("resource_type").asString(),
                v.has("retention_years") ? v.get("retention_years").asInt() : current.path("retention_years").asInt(),
                v.has("archive_after_days") ? v.get("archive_after_days").asInt() : current.path("archive_after_days").asInt());
    }

    private static void check(String resourceType, int years, int archiveDays) {
        String error = ExportRules.validateRetention(resourceType, years, archiveDays);
        if (error != null) {
            throw ApiException.unprocessable(error, switch (error) {
                case "invalid_resource_type" -> "resource_type must be * or a lower-case resource name";
                case "invalid_retention_years" -> "retention_years must be between 1 and 100";
                default -> "archive_after_days must be >= 0 and shorter than the retention period";
            });
        }
    }
}
