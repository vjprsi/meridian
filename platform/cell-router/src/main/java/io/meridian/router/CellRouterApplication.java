package io.meridian.router;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Cell router: routes every /v1 request to the cell that hosts the caller's tenant (S20). Stateless; its only state
 * is a placement cache rebuilt from the tenant registry.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CellRouterApplication {
    public static void main(String[] args) {
        SpringApplication.run(CellRouterApplication.class, args);
    }
}
