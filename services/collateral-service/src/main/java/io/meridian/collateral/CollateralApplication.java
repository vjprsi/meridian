package io.meridian.collateral;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** collateral-service: Collateral registry: pledged assets and guarantees, valuations, links to exposures with LTV, insurance policies and release / liquidation. */
@SpringBootApplication
public class CollateralApplication {
    public static void main(String[] args) {
        SpringApplication.run(CollateralApplication.class, args);
    }
}
