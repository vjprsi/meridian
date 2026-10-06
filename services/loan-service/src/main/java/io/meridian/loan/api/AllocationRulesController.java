package io.meridian.loan.api;

import io.meridian.common.error.ApiException;
import io.meridian.common.tenancy.TenantContext;
import io.meridian.loan.api.generated.AllocationRulesApi;
import io.meridian.loan.api.generated.model.AllocationRule;
import io.meridian.loan.api.generated.model.AllocationRuleCreate;
import io.meridian.loan.api.generated.model.AllocationRuleUpdate;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Allocation waterfall rules: component order validated (no duplicates; principal and interest present). */
@RestController
public class AllocationRulesController implements AllocationRulesApi {
    private final CrudEngine crud;
    private final Actions actions;

    public AllocationRulesController(CrudEngine crud, Actions actions) {
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<AllocationRule> allocationRulesCreate(String idempotencyKey, AllocationRuleCreate body) {
        TenantContext.require();
        validate(actions.tree(body));
        return crud.create("allocation_rule", AllocationRule.class, body, idempotencyKey, Crud.params());
    }

    @Override
    public ResponseEntity<AllocationRule> allocationRulesUpdate(UUID id, String ifMatch, AllocationRuleUpdate body) {
        TenantContext.require();
        validate(actions.tree(body));
        return crud.update("allocation_rule", AllocationRule.class, body, ifMatch, Crud.params().with("id", id));
    }

    static void validate(JsonNode req) {
        JsonNode order = req.get("order_of_components");
        if (order != null && !order.isNull()) {
            Set<String> seen = new HashSet<>();
            for (JsonNode c : order) {
                if (!seen.add(c.asString())) {
                    throw ApiException.unprocessable("duplicate_component", "Component " + c.asString() + " is listed twice");
                }
            }
            if (!seen.contains("PRINCIPAL") || !seen.contains("INTEREST")) {
                throw ApiException.unprocessable("incomplete_waterfall", "order_of_components must include PRINCIPAL and INTEREST");
            }
        }
        String from = Http.text(req, "valid_from");
        String to = Http.text(req, "valid_to");
        if (from != null && to != null && LocalDate.parse(to).isBefore(LocalDate.parse(from))) {
            throw ApiException.unprocessable("invalid_validity", "valid_to must not be before valid_from");
        }
    }
}
