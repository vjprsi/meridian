plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.identity"
    database = "identity_db"
}
