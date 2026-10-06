plugins { id("meridian.library-conventions") }

dependencies {
    api(platform(libs.testcontainers.bom))
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.boot:spring-boot-testcontainers")
    api("org.testcontainers:testcontainers-postgresql")
    api("org.testcontainers:testcontainers-kafka")
    api("org.testcontainers:testcontainers-junit-jupiter")
}
