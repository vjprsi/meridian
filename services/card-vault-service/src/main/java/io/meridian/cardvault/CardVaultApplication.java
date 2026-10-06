package io.meridian.cardvault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** card-vault-service: PCI DSS scoped card data vault. Stores PAN, CVV2 and expiry encrypted with envelope encryption under HSM-managed keys and returns tokens. Isolated network segment; only authorisati */
@SpringBootApplication
public class CardVaultApplication {
    public static void main(String[] args) {
        SpringApplication.run(CardVaultApplication.class, args);
    }
}
