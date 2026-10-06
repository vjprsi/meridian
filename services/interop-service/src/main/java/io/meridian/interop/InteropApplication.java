package io.meridian.interop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** interop-service: Interoperability with national switches, Mojaloop hubs and partner wallets/banks: participant directory, party lookup, quotes, two-phase transfers (reserve, commit, abort) with liq */
@SpringBootApplication
public class InteropApplication {
    public static void main(String[] args) {
        SpringApplication.run(InteropApplication.class, args);
    }
}
