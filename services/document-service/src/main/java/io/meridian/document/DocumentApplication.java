package io.meridian.document;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** document-service: Encrypted document storage with virus scanning, classification and retention / legal hold; templates and document generation (statements, contracts, letters); electronic signatures */
@SpringBootApplication
public class DocumentApplication {
    public static void main(String[] args) {
        SpringApplication.run(DocumentApplication.class, args);
    }
}
