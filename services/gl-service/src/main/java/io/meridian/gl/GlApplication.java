package io.meridian.gl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** gl-service: Chart of accounts per legal entity, product-to-GL mapping, real-time GL aggregation from ledger events, accounting periods and continuous close, trial balance, IFRS 9 provisions an */
@SpringBootApplication
public class GlApplication {
    public static void main(String[] args) {
        SpringApplication.run(GlApplication.class, args);
    }
}
