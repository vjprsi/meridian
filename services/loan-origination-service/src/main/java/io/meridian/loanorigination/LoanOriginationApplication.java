package io.meridian.loanorigination;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** loan-origination-service: Loan and credit-line applications from any channel: applicants, declared finances, documents, credit bureau pulls, scorecards with explainable reason codes, affordability, decision */
@SpringBootApplication
public class LoanOriginationApplication {
    public static void main(String[] args) {
        SpringApplication.run(LoanOriginationApplication.class, args);
    }
}
