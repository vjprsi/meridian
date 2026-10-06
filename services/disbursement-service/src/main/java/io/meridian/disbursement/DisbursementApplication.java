package io.meridian.disbursement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** disbursement-service: Government-to-person, payroll, social-transfer and humanitarian bulk disbursements: programs with funding wallets, batch upload, beneficiary validation (wallet exists, name match,  */
@SpringBootApplication
public class DisbursementApplication {
    public static void main(String[] args) {
        SpringApplication.run(DisbursementApplication.class, args);
    }
}
