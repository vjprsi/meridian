package io.meridian.reconciliation.application;

import io.meridian.common.error.ApiException;
import io.meridian.reconciliation.domain.Matcher;
import io.meridian.reconciliation.infrastructure.ReconStore;
import io.meridian.web.crud.CrudEngine;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Matching rules: only executable configurations are accepted. */
@Service
public class RuleService {
    private static final String T = "recon_rule";
    private static final Set<String> SUPPORTED = Set.of("ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE");
    private static final int MAX_DATE_TOLERANCE = 31;

    private final ReconStore store;
    private final CrudEngine crud;

    public RuleService(ReconStore store, CrudEngine crud) {
        this.store = store;
        this.crud = crud;
    }

    @Transactional
    public String create(Object body) {
        ObjectNode v = crud.toColumns(T, body);
        validate(v);
        return crud.insert(T, v);
    }

    @Transactional
    public String update(UUID id, String ifMatch, Object body) {
        int version = Fields.ifMatch(ifMatch);
        JsonNode cur = store.requireLocked(T, id);
        ObjectNode patch = crud.toColumns(T, body);
        ObjectNode merged = ((ObjectNode) cur).deepCopy();
        patch.propertyNames().forEach(n -> merged.set(n, patch.get(n)));
        validate(merged);
        return crud.updateRow(T, Map.of("id", id), patch, version)
                .orElseThrow(() -> ApiException.conflict("version_conflict",
                        "If-Match " + version + " does not match the current version; reload and retry"));
    }

    private void validate(JsonNode r) {
        String type = Fields.requireText(r, "match_type");
        if (!SUPPORTED.contains(type)) {
            throw ApiException.unprocessable("unsupported_match_type",
                    "Automatic rules support " + SUPPORTED + "; " + type + " matches are created via POST /matches");
        }
        JsonNode fields = r.path("match_fields").path("fields");
        if (!fields.isArray() || fields.isEmpty()) {
            throw Fields.invalid("match_fields", "required",
                    "match_fields must be a non-empty array of field names (or {\"fields\": [...]})");
        }
        boolean hasReference = false;
        for (JsonNode f : fields) {
            if (!Matcher.FIELDS.contains(f.asString())) {
                throw Fields.invalid("match_fields", "invalid_enum",
                        "Unknown match field " + f.asString() + "; allowed: " + Matcher.FIELDS);
            }
            hasReference |= Matcher.REFERENCE_FIELDS.contains(f.asString());
        }
        if (!"ONE_TO_ONE".equals(type) && !hasReference) {
            throw ApiException.unprocessable("reference_field_required",
                    type + " rules group records by reference: include end_to_end_id, external_ref or reference");
        }
        BigDecimal tol = Fields.decimal(r, "amount_tolerance");
        if (tol != null && tol.signum() < 0) {
            throw Fields.invalid("amount_tolerance", "min", "amount_tolerance must not be negative");
        }
        if (r.hasNonNull("date_tolerance_days")) {
            int d = r.get("date_tolerance_days").asInt();
            if (d < 0 || d > MAX_DATE_TOLERANCE) {
                throw Fields.invalid("date_tolerance_days", "range", "date_tolerance_days must be 0.." + MAX_DATE_TOLERANCE);
            }
        }
        UUID sourceId = Fields.uuid(r, "recon_source_id");
        if (sourceId != null && store.find("recon_source", sourceId).isEmpty()) {
            throw ApiException.unprocessable("source_not_found", "Reconciliation source " + sourceId + " does not exist");
        }
    }
}
