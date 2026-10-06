package io.meridian.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** config-service: Versioned, approval-gated configuration promoted across environments, country packs (regulatory reports, tax rules, holidays, schemes per jurisdiction) and tax rules. */
@SpringBootApplication
public class ConfigApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConfigApplication.class, args);
    }
}
