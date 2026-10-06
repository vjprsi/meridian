package io.meridian.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Single entry point for all Meridian APIs: routes each /v1 request to the service that owns the path. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EdgeGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(EdgeGatewayApplication.class, args);
    }
}
