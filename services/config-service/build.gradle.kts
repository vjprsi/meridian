plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.config"
    database = "config_db"
}
