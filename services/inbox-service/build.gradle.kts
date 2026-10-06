plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.inbox"
    database = "inbox_db"
}
