plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.openbanking"
    database = "openbanking_db"
}

dependencies {
    // Test certificates (eIDAS QCStatements) are built with BouncyCastle's ASN.1 classes.
    "testImplementation"("org.bouncycastle:bcprov-jdk18on:1.85.2")
}
