// Development / single-entry edge gateway. Production edge is Envoy or Kong (see functional spec section 8);
// this gateway gives local developers and tests one URL for every /v1 endpoint.
plugins { id("meridian.boot-app") }

dependencies {
    implementation(project(":libs:meridian-common"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
}

// Route table and bundled contract come from the generator output.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.file("generated/routes.json")) { into("meridian") }
    from(rootProject.layout.projectDirectory.file("generated/openapi/meridian.openapi.yaml")) { into("meridian") }
}
