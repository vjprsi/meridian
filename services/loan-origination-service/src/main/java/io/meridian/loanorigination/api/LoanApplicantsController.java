package io.meridian.loanorigination.api;

import io.meridian.common.tenancy.TenantContext;
import io.meridian.loanorigination.api.generated.LoanApplicantsApi;
import io.meridian.loanorigination.api.generated.model.LoanApplicant;
import io.meridian.loanorigination.api.generated.model.LoanApplicantCreate;
import io.meridian.loanorigination.api.generated.model.LoanApplicantUpdate;
import io.meridian.loanorigination.application.ApplicationService;
import io.meridian.web.crud.Crud;
import io.meridian.web.crud.CrudEngine;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Applicants of an application; changes only until the application is decided. */
@RestController
public class LoanApplicantsController implements LoanApplicantsApi {
    private final ApplicationService applications;
    private final CrudEngine crud;
    private final Actions actions;

    public LoanApplicantsController(ApplicationService applications, CrudEngine crud, Actions actions) {
        this.applications = applications;
        this.crud = crud;
        this.actions = actions;
    }

    @Override
    public ResponseEntity<LoanApplicant> loanApplicantsCreate(UUID loanApplicationId, String idempotencyKey,
                                                             LoanApplicantCreate body) {
        return actions.create(idempotencyKey, java.util.Map.of("application", loanApplicationId.toString(),
                        "body", actions.tree(body)), LoanApplicant.class,
                () -> applications.createApplicant(loanApplicationId, body));
    }

    @Override
    public ResponseEntity<LoanApplicant> loanApplicantsUpdate(UUID loanApplicationId, UUID id, String ifMatch,
                                                             LoanApplicantUpdate body) {
        TenantContext.require();
        applications.checkApplicant(loanApplicationId, actions.tree(body), id);
        return crud.update("loan_applicant", LoanApplicant.class, body, ifMatch,
                Crud.params().with("loan_application_id", loanApplicationId).with("id", id));
    }

    @Override
    public ResponseEntity<Void> loanApplicantsDelete(UUID loanApplicationId, UUID id) {
        TenantContext.require();
        applications.checkApplicantDelete(loanApplicationId, id);
        return crud.delete("loan_applicant", Crud.params().with("loan_application_id", loanApplicationId).with("id", id));
    }
}
