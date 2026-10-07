package io.meridian.customerchannel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** customer-channel-service: Customer channel BFF behind /v1/me. Stateless towards domain data (reads and acts through the owning services); keeps only the audit trail of what each customer did or was refused. */
@SpringBootApplication
public class CustomerChannelApplication {
    public static void main(String[] args) {
        SpringApplication.run(CustomerChannelApplication.class, args);
    }
}
