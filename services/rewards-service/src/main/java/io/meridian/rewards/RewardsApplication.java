package io.meridian.rewards;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** rewards-service: Loyalty programs: earn rules on spend and behaviour, points accounts with an append-only points ledger, cashback, tiers, a redemption catalogue, and referral programs with qualific */
@SpringBootApplication
public class RewardsApplication {
    public static void main(String[] args) {
        SpringApplication.run(RewardsApplication.class, args);
    }
}
