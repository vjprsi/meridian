package io.meridian.transactionmonitoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** transaction-monitoring-service: Streaming (Flink) and batch AML transaction monitoring scenarios with versioned thresholds, alerts with contributing transactions, customer AML risk rating, and an entity graph for */
@SpringBootApplication
public class TransactionMonitoringApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransactionMonitoringApplication.class, args);
    }
}
