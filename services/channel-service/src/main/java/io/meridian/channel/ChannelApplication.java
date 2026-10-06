package io.meridian.channel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** channel-service: USSD and SMS channels for feature phones: per-tenant, per-language menu trees with versioning and a simulator, MNO gateway callbacks and session state, SMS keyword commands, and an */
@SpringBootApplication
public class ChannelApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChannelApplication.class, args);
    }
}
