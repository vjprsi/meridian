plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.audit"
    database = "audit_db"
}
