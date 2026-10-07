// Applied to every Java module: toolchain, compiler flags, test platform.
plugins { java }

group = "io.meridian"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories { mavenCentral() }

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(platform(libs.findLibrary("spring-boot-bom").get()))
    // GAP-04 SCA: patched versions above the Spring Boot BOM (CI fails on fixable CRITICAL findings); remove once
    // the BOM catches up. Constraints only raise versions where the module is on the classpath anyway.
    "implementation"(platform(libs.findLibrary("jackson-bom").get()))
    constraints {
        for (lib in listOf("tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket")) {
            "implementation"(libs.findLibrary(lib).get())
        }
    }
    "testImplementation"(platform(libs.findLibrary("testcontainers-bom").get()))
    "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    "testImplementation"(libs.findLibrary("jqwik").get())
    "testImplementation"(libs.findLibrary("archunit").get())
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-serial,-processing,-this-escape,-deprecation,-dangling-doc-comments"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform { includeEngines("junit-jupiter", "jqwik") }
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 4).coerceAtLeast(1)
    systemProperty("user.timezone", "UTC")
    // Testcontainers: on this Windows host Docker runs inside WSL and is relayed on tcp://localhost:2375.
    // 127.0.0.1, not localhost: WSL forwards IPv4 loopback reliably, ::1 not always (containers would be unreachable)
    environment("DOCKER_HOST", System.getenv("DOCKER_HOST") ?: "tcp://127.0.0.1:2375")
    environment("TESTCONTAINERS_HOST_OVERRIDE", System.getenv("TESTCONTAINERS_HOST_OVERRIDE") ?: "127.0.0.1")
    environment("TESTCONTAINERS_RYUK_DISABLED", System.getenv("TESTCONTAINERS_RYUK_DISABLED") ?: "false")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
