package io.meridian.loan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** loan-service: Loan and credit-line servicing: disbursement, schedules, daily accrual, repayment allocation waterfall, fees, rate changes, restructuring, prepayment, delinquency (DPD) tracking, w */
@SpringBootApplication
public class LoanApplication {
    public static void main(String[] args) {
        SpringApplication.run(LoanApplication.class, args);
    }
}
