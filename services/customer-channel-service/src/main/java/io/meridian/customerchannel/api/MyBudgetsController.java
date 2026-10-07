package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyBudgetsApi;
import io.meridian.customerchannel.api.generated.model.Budget;
import io.meridian.customerchannel.api.generated.model.BudgetPage;
import io.meridian.customerchannel.api.generated.model.BudgetPeriod;
import io.meridian.customerchannel.api.generated.model.MyBudgetsCreateRequest;
import io.meridian.customerchannel.application.EngagementService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer's budgets. */
@RestController
public class MyBudgetsController implements MyBudgetsApi {
    private final EngagementService engagement;
    private final Http http;

    public MyBudgetsController(EngagementService engagement, Http http) {
        this.engagement = engagement;
        this.http = http;
    }

    @Override
    public ResponseEntity<BudgetPage> myBudgetsList(Integer limit, String cursor) {
        return http.ok(engagement.budgets(limit, cursor), BudgetPage.class);
    }

    @Override
    public ResponseEntity<Budget> myBudgetsCreate(String idempotencyKey, MyBudgetsCreateRequest body) {
        return http.ok(engagement.createBudget(http.body(body), idempotencyKey), Budget.class);
    }

    @Override
    public ResponseEntity<BudgetPeriod> myBudgetsCurrentPeriod(UUID budgetId) {
        return http.ok(engagement.currentPeriod(budgetId), BudgetPeriod.class);
    }

    @Override
    public ResponseEntity<Budget> myBudgetsArchive(UUID budgetId, String idempotencyKey) {
        return http.ok(engagement.archiveBudget(budgetId, idempotencyKey), Budget.class);
    }
}
