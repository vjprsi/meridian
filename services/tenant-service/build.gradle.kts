plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.tenant"
    database = "tenant_db"
}
