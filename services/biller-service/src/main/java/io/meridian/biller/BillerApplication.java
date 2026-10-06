package io.meridian.biller;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** biller-service: Biller catalogue (utilities, telecom, TV, government, education), airtime and bundle products, bill inquiry and validation, bill payments and airtime top-ups with provider integrat */
@SpringBootApplication
public class BillerApplication {
    public static void main(String[] args) {
        SpringApplication.run(BillerApplication.class, args);
    }
}
