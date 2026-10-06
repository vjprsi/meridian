package io.meridian.businessday;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** business-day-service: Owns the business date per legal entity, orchestrates EOD/EOM/EOQ/EOY job graphs with dependencies, parallelism, retries and restart-from-failure, performs the date roll, and sched */
@SpringBootApplication
public class BusinessDayApplication {
    public static void main(String[] args) {
        SpringApplication.run(BusinessDayApplication.class, args);
    }
}
