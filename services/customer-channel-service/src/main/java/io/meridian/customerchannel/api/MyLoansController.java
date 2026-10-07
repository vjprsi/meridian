package io.meridian.customerchannel.api;

import io.meridian.customerchannel.api.generated.MyLoansApi;
import io.meridian.customerchannel.api.generated.model.Loan;
import io.meridian.customerchannel.api.generated.model.LoanPage;
import io.meridian.customerchannel.api.generated.model.LoanRepayment;
import io.meridian.customerchannel.api.generated.model.LoanRepaymentPage;
import io.meridian.customerchannel.api.generated.model.MyLoansRepayRequest;
import io.meridian.customerchannel.api.generated.model.ScheduleInstallmentPage;
import io.meridian.customerchannel.application.LendingWalletService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The customer's loans. */
@RestController
public class MyLoansController implements MyLoansApi {
    private final LendingWalletService loans;
    private final Http http;

    public MyLoansController(LendingWalletService loans, Http http) {
        this.loans = loans;
        this.http = http;
    }

    @Override
    public ResponseEntity<LoanPage> myLoansList(Integer limit, String cursor) {
        return http.ok(loans.loans(limit, cursor), LoanPage.class);
    }

    @Override
    public ResponseEntity<Loan> myLoansGet(UUID loanId) {
        return http.ok(loans.loan(loanId), Loan.class);
    }

    @Override
    public ResponseEntity<ScheduleInstallmentPage> myLoansSchedule(UUID loanId, Integer limit, String cursor) {
        return http.ok(loans.schedule(loanId, limit, cursor), ScheduleInstallmentPage.class);
    }

    @Override
    public ResponseEntity<LoanRepaymentPage> myLoansRepayments(UUID loanId, Integer limit, String cursor) {
        return http.ok(loans.repayments(loanId, limit, cursor), LoanRepaymentPage.class);
    }

    @Override
    public ResponseEntity<LoanRepayment> myLoansRepay(UUID loanId, String idempotencyKey, MyLoansRepayRequest body) {
        return http.ok(loans.repay(loanId, http.body(body), idempotencyKey), LoanRepayment.class);
    }
}
