package io.meridian.collections;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** collections-service: Collections and recovery for loans, credit lines, cards, overdrafts and BNPL: strategies by segment and DPD, automated contact actions, agent queues, promises to pay, payment plans */
@SpringBootApplication
public class CollectionsApplication {
    public static void main(String[] args) {
        SpringApplication.run(CollectionsApplication.class, args);
    }
}
