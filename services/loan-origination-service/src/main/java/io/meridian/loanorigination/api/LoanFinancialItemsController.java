package io.meridian.loanorigination.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.loanorigination.api.generated.LoanFinancialItemsApi;
import io.meridian.loanorigination.api.generated.model.LoanFinancialItem;
import io.meridian.loanorigination.api.generated.model.LoanFinancialItemCreate;
import io.meridian.loanorigination.api.generated.model.LoanFinancialItemPage;
import io.meridian.loanorigination.api.generated.model.LoanFinancialItemUpdate;
import io.meridian.loanorigination.application.ApplicationService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Declared income and expenses of an application's applicants. The table references the applicant, so every
 * operation resolves the application in the path through loan_applicant (the generic CRUD would ignore it).
 */
@RestController
public class LoanFinancialItemsController implements LoanFinancialItemsApi {
    private static final String TABLE = "loan_financial_item";

    private final ApplicationService applications;
    private final CrudEngine crud;
    private final Actions actions;

    public LoanFinancialItemsController(ApplicationService applications, CrudEngine crud, Actions actions) {
        this.applications = applications;
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<LoanFinancialItem> loanFinancialItemsCreate(UUID loanApplicationId, String idempotencyKey,
                                                                     LoanFinancialItemCreate body) {
        return actions.create(idempotencyKey, Map.of("application", loanApplicationId.toString(), "body", actions.tree(body)),
                LoanFinancialItem.class, () -> applications.createFinancialItem(loanApplicationId, body));
    }

    @Override
    public ResponseEntity<LoanFinancialItemPage> loanFinancialItemsList(UUID loanApplicationId, Integer limit, String cursor,
                                                                       OffsetDateTime createdAfter, OffsetDateTime createdBefore,
                                                                       UUID loanApplicantId, String itemType, Boolean isVerified,
                                                                       String customField1, String customField2,
                                                                       String customField3, String customField4,
                                                                       String customField5) {
        TenantContext.require();
        return ResponseEntity.ok(actions.model(applications.listFinancialItems(loanApplicationId, limit, cursor,
                loanApplicantId, itemType, isVerified), LoanFinancialItemPage.class));
    }

    @Override
    public ResponseEntity<LoanFinancialItem> loanFinancialItemsGet(UUID loanApplicationId, UUID id) {
        TenantContext.require();
        applications.requireFinancialItem(loanApplicationId, id);
        return crud.get(TABLE, LoanFinancialItem.class, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<LoanFinancialItem> loanFinancialItemsUpdate(UUID loanApplicationId, UUID id, String ifMatch,
                                                                     LoanFinancialItemUpdate body) {
        TenantContext.require();
        applications.requireFinancialItem(loanApplicationId, id);
        applications.checkFinancialItem(loanApplicationId, actions.tree(body));
        return crud.update(TABLE, LoanFinancialItem.class, body, ifMatch, Crud.params().with("id", id));
    }

    @Override
    public ResponseEntity<Void> loanFinancialItemsDelete(UUID loanApplicationId, UUID id) {
        TenantContext.require();
        applications.requireFinancialItem(loanApplicationId, id);
        applications.requireEditable(loanApplicationId);
        return crud.delete(TABLE, Crud.params().with("id", id));
    }
}
