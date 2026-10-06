plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.deposit"
    database = "accounts_db"
}
