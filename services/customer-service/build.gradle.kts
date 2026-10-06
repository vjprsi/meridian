plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.customer"
    database = "customer_db"
}
