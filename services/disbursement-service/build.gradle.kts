plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.disbursement"
    database = "disbursement_db"
}
