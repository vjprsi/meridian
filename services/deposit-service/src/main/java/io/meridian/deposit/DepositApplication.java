package io.meridian.deposit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** deposit-service: Owns deposit accounts and everything around them. Opens a ledger account per deposit account, drives daily interest accrual and capitalisation, fee charging, overdrafts, term depos */
@SpringBootApplication
public class DepositApplication {
    public static void main(String[] args) {
        SpringApplication.run(DepositApplication.class, args);
    }
}
