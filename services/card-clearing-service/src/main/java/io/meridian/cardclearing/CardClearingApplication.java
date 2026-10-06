package io.meridian.cardclearing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** card-clearing-service: Imports scheme clearing files (Visa BASE II, Mastercard IPM/T112), matches presentments to authorisations, posts final amounts to the ledger, computes interchange and fees, and tra */
@SpringBootApplication
public class CardClearingApplication {
    public static void main(String[] args) {
        SpringApplication.run(CardClearingApplication.class, args);
    }
}
