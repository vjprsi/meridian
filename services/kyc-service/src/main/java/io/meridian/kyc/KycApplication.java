package io.meridian.kyc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** kyc-service: KYC/KYB orchestration: document capture and OCR, liveness and face match, database and registry checks, UBO discovery, PEP/sanctions/adverse-media screening at onboarding, risk rat */
@SpringBootApplication
public class KycApplication {
    public static void main(String[] args) {
        SpringApplication.run(KycApplication.class, args);
    }
}
