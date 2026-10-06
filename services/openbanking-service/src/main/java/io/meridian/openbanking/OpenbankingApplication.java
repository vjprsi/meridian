package io.meridian.openbanking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** openbanking-service: ASPSP open banking gateway. Validates TPP identity (eIDAS / directory certificates), manages consents and SCA redirect / decoupled flows, enforces consent scope on every call, and  */
@SpringBootApplication
public class OpenbankingApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpenbankingApplication.class, args);
    }
}
