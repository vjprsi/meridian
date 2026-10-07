// Spring Boot application that is not a spec-driven domain service (e.g. the edge gateway).
plugins {
    id("meridian.java-conventions")
    id("org.springframework.boot")
}

dependencies {
    // GAP-10: every Boot app serves /actuator/prometheus (on its management port, never the routed one: SEC-31)
    "implementation"("org.springframework.boot:spring-boot-starter-actuator")
    "implementation"("io.micrometer:micrometer-registry-prometheus")
}
