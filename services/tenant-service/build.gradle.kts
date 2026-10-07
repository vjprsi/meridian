plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.tenant"
    database = "tenant_db"
}

dependencies {
    // starter-packs/*.yaml (tenant bootstrap definitions)
    "implementation"("org.yaml:snakeyaml")
}
