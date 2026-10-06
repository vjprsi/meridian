plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.billing"
    database = "billing_db"
}
