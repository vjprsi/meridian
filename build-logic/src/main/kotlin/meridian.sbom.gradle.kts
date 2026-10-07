// GAP-04 supply chain: CycloneDX SBOMs of what every module ships (runtimeClasspath after conflict resolution).
// Applied to the root project only (build.gradle.kts):
//   ./gradlew cyclonedxBom                                  aggregate SBOM  build/reports/cyclonedx/bom.{json,xml}
//   ./gradlew :services:ledger-service:cyclonedxDirectBom   one module      <module>/build/reports/cyclonedx-direct/
// CI uploads the aggregate as an artifact and scans it for known vulnerabilities (Trivy); images get their own SBOM
// attested with cosign on main (.github/workflows/ci.yml).
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component

plugins { id("org.cyclonedx.bom") }

allprojects {
    tasks.withType<CyclonedxDirectTask>().configureEach {
        // shipped dependencies only (no test, annotation-processor or tooling configurations)
        includeConfigs = listOf("runtimeClasspath")
        projectType = if (path.startsWith(":services:") || path.startsWith(":platform:")) Component.Type.APPLICATION
                      else Component.Type.LIBRARY
    }
}

tasks.named<org.cyclonedx.gradle.CyclonedxAggregateTask>("cyclonedxBom") {
    projectType = Component.Type.APPLICATION
    componentName = "meridian-core"
}
