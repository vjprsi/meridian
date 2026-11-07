// Bre-B simulator: an in-memory stand-in for the Colombian instant payment system (Banco de la República SPBVI)
// and its key directory (DICE), served per access node so routing-service's multi-node smart routing and failover
// can be exercised in the sandbox and CI. Not a certified implementation of the BanRep technical annex.
plugins { id("meridian.boot-app") }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
