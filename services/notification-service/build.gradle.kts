plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.notification"
    database = "notification_db"
}
