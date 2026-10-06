package io.meridian.insights.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.insights.api.generated.BudgetsApi;
import io.meridian.insights.api.generated.model.Budget;
import io.meridian.insights.api.generated.model.BudgetCreate;
import io.meridian.insights.api.generated.model.BudgetPeriod;
import io.meridian.insights.api.generated.model.BudgetPeriodPage;
import io.meridian.insights.api.generated.model.BudgetUpdate;
import io.meridian.insights.api.generated.model.BudgetsSuggestRequest;
import io.meridian.insights.application.BudgetService;
import io.meridian.insights.application.Json;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Budgets: validation, periods with recomputed actuals and alerts, lifecycle, suggestions. */
@RestController
public class BudgetsController implements BudgetsApi {
    private final BudgetService budgets;
    private final Http http;

    public BudgetsController(BudgetService budgets, Http http) {
        this.budgets = budgets;
        this.http = http;
    }

    @Override
    public ResponseEntity<Budget> budgetsCreate(String idempotencyKey, BudgetCreate body) {
        return http.created(idempotencyKey, "budget.create", body, Budget.class, () -> budgets.create(body));
    }

    @Override
    public ResponseEntity<Budget> budgetsUpdate(UUID id, String ifMatch, BudgetUpdate body) {
        int version = Json.parseVersion(ifMatch);
        return http.withEtag(budgets.update(id, body, version), Budget.class);
    }

    @Override
    public ResponseEntity<Budget> budgetsPause(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "budget.pause", id, null, Budget.class, () -> budgets.transition(id, "pause"));
    }

    @Override
    public ResponseEntity<Budget> budgetsResume(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "budget.resume", id, null, Budget.class, () -> budgets.transition(id, "resume"));
    }

    @Override
    public ResponseEntity<Budget> budgetsArchive(UUID id, String idempotencyKey) {
        return http.ok(idempotencyKey, "budget.archive", id, null, Budget.class, () -> budgets.transition(id, "archive"));
    }

    @Override
    public ResponseEntity<BudgetPeriodPage> budgetsPeriods(UUID id, Integer limit, String cursor) {
        TenantContext.require();
        return ResponseEntity.ok(http.read(budgets.periods(id, limit, cursor), BudgetPeriodPage.class));
    }

    @Override
    public ResponseEntity<BudgetPeriod> budgetsCurrentPeriod(UUID id) {
        TenantContext.require();
        return http.withEtag(budgets.currentPeriod(id), BudgetPeriod.class);
    }

    @Override
    public ResponseEntity<Map<String, Object>> budgetsSuggest(String idempotencyKey, BudgetsSuggestRequest body) {
        JsonNode req = http.tree(body);
        UUID customer = Json.uuid(req, "customer_id");
        int months = Json.integer(req, "months", 3);
        return http.document(idempotencyKey, "budget.suggest", null, body, () -> budgets.suggest(customer, months));
    }
}
