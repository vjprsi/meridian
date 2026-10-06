package io.meridian.referencedata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** reference-data-service: Golden source of reference data: currencies, countries, languages, business and rail calendars, FX rates and their sources, bank directory (BIC / national clearing codes), MCC and  */
@SpringBootApplication
public class ReferenceDataApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReferenceDataApplication.class, args);
    }
}
