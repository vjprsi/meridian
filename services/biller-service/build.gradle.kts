plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.biller"
    database = "biller_db"
}
