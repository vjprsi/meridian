plugins { id("meridian.library-conventions") }

dependencies {
    api(project(":libs:meridian-common"))
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-kafka")
    api("org.springframework.boot:spring-boot-starter-jackson")
    implementation("io.micrometer:micrometer-core")   // outbox relay metrics (GAP-10)
    // MR-GAME-DAY: retention / replay / standby promotion against real PostgreSQL
    testImplementation(project(":libs:meridian-test"))
    testRuntimeOnly("org.postgresql:postgresql")
}
