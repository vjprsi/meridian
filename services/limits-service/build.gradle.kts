plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.limits"
    database = "limits_db"
}
