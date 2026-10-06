package io.meridian.fx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** fx-service: FX quotes with expiry and margin, FX deal booking, remittance corridors with partner routing and payout methods, and remittance partner configuration. */
@SpringBootApplication
public class FxApplication {
    public static void main(String[] args) {
        SpringApplication.run(FxApplication.class, args);
    }
}
