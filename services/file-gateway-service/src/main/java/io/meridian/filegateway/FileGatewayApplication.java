package io.meridian.filegateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** file-gateway-service: Managed file transfer with banks, schemes, bureaus, regulators and partners over SFTP, FTPS, S3, AS2 and APIs: PGP encryption, virus scanning, format validation, routing of inbound */
@SpringBootApplication
public class FileGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(FileGatewayApplication.class, args);
    }
}
