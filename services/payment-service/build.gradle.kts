plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.payment"
    database = "payment_db"
}
