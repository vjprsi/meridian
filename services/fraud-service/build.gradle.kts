plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.fraud"
    database = "fraud_db"
}
