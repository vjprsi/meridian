// Data mover (MR-GAME-DAY): copies one tenant's data from its source cell to a target cell for a live tenant move.
// Consumes platform.tenant_move.copy_requested (tenant-service), copies the tenant's rows of every service database
// through the services' internal data-lifecycle move API (RLS-scoped extract + idempotent bulk import) and the
// tenant's documents (S3 prefix copy), verifies per-table row counts and content checksums, and reports the
// checkpoint to tenant-service (/confirm-copy). Resumable (progress in its own small database), idempotent, throttled.
plugins { id("meridian.boot-app") }

dependencies {
    implementation(project(":libs:meridian-common"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.flywaydb:flyway-core")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
    // the cells' services in the IT: the real data-lifecycle move API (libs/meridian-web) over two databases
    testImplementation(project(":libs:meridian-web"))
    testImplementation(project(":libs:meridian-test"))      // TestRows: minimal valid rows in any schema
}

// Which services own data (name, port, database) comes from the generated services manifest.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.file("generated/services.json")) { into("meridian") }
}
