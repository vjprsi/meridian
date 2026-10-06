package io.meridian.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** wallet-service: Wallet lifecycle, KYC-tier limits, PINs, linked bank accounts and every wallet money movement (P2P, cash-in/out, merchant, bill, airtime, G2P, wallet-to-bank, reversals). Each tran */
@SpringBootApplication
public class WalletApplication {
    public static void main(String[] args) {
        SpringApplication.run(WalletApplication.class, args);
    }
}
