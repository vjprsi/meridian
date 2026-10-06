// Load tests run against a live service (python tools/dev.py up ledger-service).
//   ./gradlew :platform:load-tests:run --args="--base http://localhost:9110 --seconds 60 --concurrency 64 --accounts 200"
plugins {
    id("meridian.java-conventions")
    application
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation("tools.jackson.core:jackson-databind")
}

application {
    mainClass = "io.meridian.load.LedgerLoadTest"
    applicationDefaultJvmArgs = listOf("-XX:+UseZGC")
}

tasks.named<JavaExec>("run") {
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
}
