plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.ledger"
    database = "ledger_db"
}
