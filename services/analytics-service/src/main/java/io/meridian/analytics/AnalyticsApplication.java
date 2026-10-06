package io.meridian.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** analytics-service: Self-service analytics over the Iceberg lakehouse via Trino: KPI catalogue, dashboards and widgets embedded in the console, saved parameterised queries, exports and scheduled data  */
@SpringBootApplication
public class AnalyticsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}
