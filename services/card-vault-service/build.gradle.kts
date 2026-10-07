plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.cardvault"
    database = "card_vault_db"
}

dependencies {
    // GAP-01: AWS KMS envelope encryption (key-management=aws-kms); URL-connection HTTP client only (no Netty/Apache)
    implementation(platform(libs.awssdk.bom))
    implementation("software.amazon.awssdk:kms") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")
}
