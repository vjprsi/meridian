package io.meridian.treasurydeal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** treasury-deal-service: Front and middle office for treasury: counterparties and limits, money market, FX and securities deals, confirmations (MT300/MT320) and settlement instructions; posts to the ledger */
@SpringBootApplication
public class TreasuryDealApplication {
    public static void main(String[] args) {
        SpringApplication.run(TreasuryDealApplication.class, args);
    }
}
