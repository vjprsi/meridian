plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.transactionmonitoring"
    database = "tm_db"
}
