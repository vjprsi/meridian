package io.meridian.limits;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** limits-service: Central limits engine: amount, count and balance limits by scope (tier, product, customer, account, card, wallet, agent), channel, transaction type and period; atomic check-and-res */
@SpringBootApplication
public class LimitsApplication {
    public static void main(String[] args) {
        SpringApplication.run(LimitsApplication.class, args);
    }
}
