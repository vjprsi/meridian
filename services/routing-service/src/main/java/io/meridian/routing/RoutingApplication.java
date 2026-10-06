package io.meridian.routing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** routing-service: Rail catalogue and adapters, cut-offs and calendars, smart routing (cost, speed, success rate), raw rail message store, settlement batches, nostro accounts and intraday liquidity p */
@SpringBootApplication
public class RoutingApplication {
    public static void main(String[] args) {
        SpringApplication.run(RoutingApplication.class, args);
    }
}
