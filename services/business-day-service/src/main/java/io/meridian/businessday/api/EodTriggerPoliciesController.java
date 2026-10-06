package io.meridian.businessday.api;

import io.meridian.businessday.api.generated.EodTriggerPoliciesApi;
import io.meridian.businessday.api.generated.model.EodTriggerPolicy;
import io.meridian.businessday.api.generated.model.EodTriggerPolicyCreate;
import io.meridian.businessday.api.generated.model.EodTriggerPolicyUpdate;
import io.meridian.businessday.application.EodTriggerService;
import io.meridian.businessday.domain.EodTrigger;
import io.meridian.businessday.infrastructure.Rows;
import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.web.crud.CrudEngine;
import io.meridian.web.idempotency.Idempotency;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Automatic EOD trigger policies: one per legal entity plus an optional tenant default (no legal entity).
 * Cut-off time and time zone are validated; {@code GET /evaluation} shows what the trigger would do right now.
 */
@RestController
public class EodTriggerPoliciesController implements EodTriggerPoliciesApi {
    private static final String TABLE = "eod_trigger_policy";

    private final CrudEngine crud;
    private final Rows rows;
    private final TransactionTemplate tx;
    private final Idempotency idempotency;
    private final EodTriggerService trigger;
    private final JsonMapper json;

    public EodTriggerPoliciesController(CrudEngine crud, Rows rows, TransactionTemplate tx, Idempotency idempotency,
                                        EodTriggerService trigger, JsonMapper json) {
        this.crud = crud;
        this.rows = rows;
        this.tx = tx;
        this.idempotency = idempotency;
        this.trigger = trigger;
        this.json = json;
    }

    @Override
    public ResponseEntity<EodTriggerPolicy> eodTriggerPoliciesCreate(String idempotencyKey, EodTriggerPolicyCreate body) {
        TenantContext.require();
        return idempotency.execute(idempotencyKey, body, EodTriggerPolicy.class, () -> tx.execute(s -> {
            ObjectNode values = crud.toColumns(TABLE, body);
            validate(values);
            lockPolicies();
            requireUnique(null, values);
            return ResponseEntity.status(HttpStatus.CREATED).body(crud.read(crud.insert(TABLE, values), EodTriggerPolicy.class));
        }));
    }

    @Override
    public ResponseEntity<EodTriggerPolicy> eodTriggerPoliciesUpdate(UUID id, String ifMatch, EodTriggerPolicyUpdate body) {
        TenantContext.require();
        int version = Http.ifMatch(ifMatch);
        return tx.execute(s -> {
            lockPolicies();
            JsonNode current = rows.jdbc().sql("select row_to_json(p)::text from eod_trigger_policy p where id = ? for update")
                    .param(id).query(String.class).optional().map(json::readTree)
                    .orElseThrow(() -> ApiException.notFound("eod trigger policy", id));
            ObjectNode changes = crud.toColumns(TABLE, body);
            ObjectNode merged = ((ObjectNode) current).deepCopy();
            merged.setAll(changes);
            validate(merged);
            requireUnique(id, merged);
            String row = crud.updateRow(TABLE, Map.of("id", id), changes, version)
                    .orElseThrow(() -> ApiException.conflict("version_conflict",
                            "If-Match " + version + " does not match the current version; reload and retry"));
            return ResponseEntity.ok().eTag("\"" + json.readTree(row).get("row_version").asString() + "\"")
                    .body(crud.read(row, EodTriggerPolicy.class));
        });
    }

    @Override
    public ResponseEntity<Map<String, Object>> eodTriggerPoliciesEvaluation() {
        TenantContext.require();
        return ResponseEntity.ok(Http.object(json, trigger.evaluation()));
    }

    private static void validate(JsonNode values) {
        EodTrigger.parseCutOff(Http.text(values, "cut_off_time"));
        String zone = Http.text(values, "time_zone");
        if (zone != null && !zone.isBlank()) {
            EodTrigger.parseZone(zone);
        }
    }

    /** One policy per legal entity and at most one tenant default (PostgreSQL treats NULLs as distinct). */
    private void requireUnique(UUID self, JsonNode values) {
        String entity = Http.text(values, "legal_entity_id");
        Long clash = rows.jdbc().sql("""
                select count(*) from eod_trigger_policy
                where legal_entity_id is not distinct from ?::uuid and (?::uuid is null or id <> ?::uuid)""")
                .params(entity, self == null ? null : self.toString(), self == null ? null : self.toString())
                .query(Long.class).single();
        if (clash > 0) {
            throw ApiException.conflict("policy_exists", entity == null
                    ? "The tenant already has a default EOD trigger policy; update it instead"
                    : "Legal entity " + entity + " already has an EOD trigger policy; update it instead");
        }
    }

    private void lockPolicies() {
        rows.jdbc().sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) l")
                .param("eodpolicy:" + TenantContext.require()).query(Integer.class).single();
    }
}
