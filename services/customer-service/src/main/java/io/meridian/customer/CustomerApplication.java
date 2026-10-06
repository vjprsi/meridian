package io.meridian.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** customer-service: Customer information file (CIF): individuals and businesses, identifiers, addresses, contacts, relationships and UBOs, groups and households, segments, KYC tier, devices, credentia */
@SpringBootApplication
public class CustomerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CustomerApplication.class, args);
    }
}
