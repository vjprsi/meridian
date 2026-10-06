plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.webhook"
    database = "webhook_db"
}
