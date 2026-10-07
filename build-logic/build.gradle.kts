plugins { `kotlin-dsl` }

dependencies {
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.openapi.generator.gradle.plugin)
    implementation(libs.cyclonedx.gradle.plugin)   // GAP-04: SBOMs (meridian.sbom)
}
