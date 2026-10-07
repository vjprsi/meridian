package io.meridian.businesschannel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** business-channel-service: Business banking portal BFF behind /v1/business. Every operation acts for the signed-in business user only: the business (a non-individual customer of customer-service) and the use */
@SpringBootApplication
public class BusinessChannelApplication {
    public static void main(String[] args) {
        SpringApplication.run(BusinessChannelApplication.class, args);
    }
}
