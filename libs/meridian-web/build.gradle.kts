plugins { id("meridian.library-conventions") }

dependencies {
    api(project(":libs:meridian-common"))
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-actuator")
    // MR-GAME-DAY operator switches (cell role, outbox replay) over meridian-events; every service ships both libs,
    // the configuration is conditional on the class being present
    compileOnly(project(":libs:meridian-events"))
    testImplementation(project(":libs:meridian-events"))
    // MR-GAME-DAY: tenant-move extract / import against real PostgreSQL (generated service schemas)
    testImplementation(project(":libs:meridian-test"))
    // outbound calls to tenant-supplied URLs (OutboundHttp): pinned, policy-checked DNS resolution
    implementation("org.apache.httpcomponents.client5:httpclient5")
    // GAP-22 migrate-only runs (SchemaMigrator); services bring Flyway at runtime (spring-boot-starter-flyway)
    compileOnly("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-core")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
