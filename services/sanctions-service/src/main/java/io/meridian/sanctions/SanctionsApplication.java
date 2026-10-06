package io.meridian.sanctions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** sanctions-service: Loads and versions sanctions, PEP and adverse-media lists; screens customers, related parties and payments with fuzzy name matching (transliteration, phonetic, token) and manages g */
@SpringBootApplication
public class SanctionsApplication {
    public static void main(String[] args) {
        SpringApplication.run(SanctionsApplication.class, args);
    }
}
