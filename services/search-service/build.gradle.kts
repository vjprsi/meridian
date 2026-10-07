plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.search"
    database = "search_db"
}

dependencies {
    // search-mappings.yaml (indexing feed)
    "implementation"("org.yaml:snakeyaml")
}
