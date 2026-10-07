package io.meridian.businesschannel.api;

import io.meridian.businesschannel.api.generated.BusinessLoansApi;
import io.meridian.businesschannel.api.generated.model.BusinessLoansRepayRequest;
import io.meridian.businesschannel.api.generated.model.Loan;
import io.meridian.businesschannel.api.generated.model.LoanPage;
import io.meridian.businesschannel.api.generated.model.LoanRepayment;
import io.meridian.businesschannel.api.generated.model.ScheduleInstallmentPage;
import io.meridian.businesschannel.application.LendingService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Business loans. */
@RestController
public class BusinessLoansController implements BusinessLoansApi {
    private final LendingService lending;
    private final Http http;

    public BusinessLoansController(LendingService lending, Http http) {
        this.lending = lending;
        this.http = http;
    }

    @Override
    public ResponseEntity<LoanPage> businessLoansList(Integer limit, String cursor) {
        return http.ok(lending.loans(limit, cursor), LoanPage.class);
    }

    @Override
    public ResponseEntity<Loan> businessLoansGet(UUID loanId) {
        return http.ok(lending.loan(loanId), Loan.class);
    }

    @Override
    public ResponseEntity<ScheduleInstallmentPage> businessLoansSchedule(UUID loanId, Integer limit, String cursor) {
        return http.ok(lending.schedule(loanId, limit, cursor), ScheduleInstallmentPage.class);
    }

    @Override
    public ResponseEntity<LoanRepayment> businessLoansRepay(UUID loanId, String idempotencyKey,
                                                            BusinessLoansRepayRequest body) {
        return http.ok(lending.repayLoan(loanId, http.body(body), idempotencyKey), LoanRepayment.class);
    }
}
