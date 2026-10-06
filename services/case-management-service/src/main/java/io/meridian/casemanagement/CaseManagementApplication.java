package io.meridian.casemanagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** case-management-service: Investigation workspace for AML, fraud and sanctions cases: consolidates alerts, evidence, notes and tasks; records dispositions with maker-checker; drafts and files SAR/STR report */
@SpringBootApplication
public class CaseManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(CaseManagementApplication.class, args);
    }
}
