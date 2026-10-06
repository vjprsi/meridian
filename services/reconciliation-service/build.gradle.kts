plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.reconciliation"
    database = "recon_db"
}
