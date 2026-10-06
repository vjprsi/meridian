plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.pricingrules"
    database = "pricing_db"
}
