package io.meridian.card;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** card-service: Card programs, card products, card lifecycle (issue, activate, freeze, replace, close), physical card orders through bureaus, card controls and spend limits, PIN events and credit  */
@SpringBootApplication
public class CardApplication {
    public static void main(String[] args) {
        SpringApplication.run(CardApplication.class, args);
    }
}
