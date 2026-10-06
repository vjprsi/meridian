package io.meridian.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** crm-service: Cases, complaints, interactions, notes, work queues and SLAs for servicing staff, plus the knowledge base and the servicing copilot. Builds the customer timeline from its own recor */
@SpringBootApplication
public class CrmApplication {
    public static void main(String[] args) {
        SpringApplication.run(CrmApplication.class, args);
    }
}
