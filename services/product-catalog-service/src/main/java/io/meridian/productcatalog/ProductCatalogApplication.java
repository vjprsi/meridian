package io.meridian.productcatalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** product-catalog-service: Product Studio back end. Holds templates, products and immutable published versions with all parameters (interest, fees, tax, limits, eligibility, scripts). Accounts pin a version; */
@SpringBootApplication
public class ProductCatalogApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProductCatalogApplication.class, args);
    }
}
