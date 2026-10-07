// Cell router (S20): the global entry tier in front of the cells. Resolves the tenant (JWT claim, or the tenant
// parameter of public callbacks), looks up its placement in the tenant registry (cached, invalidated by
// tenant-service events) and streams the request to the gateway of the tenant's cell, failing over to the standby
// cell when the home cell is OFFLINE. Stateless; scale horizontally behind the regional load balancer.
plugins { id("meridian.boot-app") }

dependencies {
    implementation(project(":libs:meridian-common"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    // JWT signature / issuer / audience checks with the IdP's JWKS (no Spring Security filter chain: the router only
    // needs the verified tenant claim; the cell's services authorize the request)
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("io.micrometer:micrometer-registry-prometheus")

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

// Public (callback) routes come from the generator output, like the edge gateway's route table.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.file("generated/routes.json")) { into("meridian") }
}
