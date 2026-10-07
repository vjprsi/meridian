plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.regulatoryreporting"
    database = "regreport_db"
}

dependencies {
    // regulatory-packs/*.yaml (versioned regulatory pack definitions)
    "implementation"("org.yaml:snakeyaml")
}
