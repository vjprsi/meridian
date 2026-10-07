plugins { id("meridian.library-conventions") }

dependencies {
    api(project(":libs:meridian-common"))
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-actuator")
    // outbound calls to tenant-supplied URLs (OutboundHttp): pinned, policy-checked DNS resolution
    implementation("org.apache.httpcomponents.client5:httpclient5")
    // GAP-22 migrate-only runs (SchemaMigrator); services bring Flyway at runtime (spring-boot-starter-flyway)
    compileOnly("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-core")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
