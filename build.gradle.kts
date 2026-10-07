// Root project: build-wide concerns only (modules are configured by the build-logic convention plugins).
plugins { id("meridian.sbom") }   // GAP-04: CycloneDX SBOMs (./gradlew cyclonedxBom)
