plugins { id("meridian.library-conventions") }

dependencies {
    api(platform(libs.testcontainers.bom))
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.boot:spring-boot-testcontainers")
    api("org.testcontainers:testcontainers-postgresql")
    api("org.testcontainers:testcontainers-kafka")
    api("org.testcontainers:testcontainers-junit-jupiter")
    // DataLifecycleCheck (GAP-08 service-level checks)
    api("tools.jackson.core:jackson-databind")
}
