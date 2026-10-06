package io.meridian.regulatoryreporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** regulatory-reporting-service: Catalogue of regulatory reports per country pack with versioned templates and data mappings; runs reports reproducibly from lakehouse snapshots, validates them, tracks manual adjus */
@SpringBootApplication
public class RegulatoryReportingApplication {
    public static void main(String[] args) {
        SpringApplication.run(RegulatoryReportingApplication.class, args);
    }
}
