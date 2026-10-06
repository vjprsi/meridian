package io.meridian.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** search-service: Enterprise search across customers, accounts, wallets, cards, loans, payments and cases. Maintains OpenSearch indices fed by domain events (CDC), with versioned mappings, zero-down */
@SpringBootApplication
public class SearchApplication {
    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
