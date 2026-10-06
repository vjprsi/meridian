package io.meridian.archive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** archive-service: Data lifecycle management: data classification catalogue, retention policies per data class and jurisdiction, archival of aged records to immutable object storage with manifests, l */
@SpringBootApplication
public class ArchiveApplication {
    public static void main(String[] args) {
        SpringApplication.run(ArchiveApplication.class, args);
    }
}
