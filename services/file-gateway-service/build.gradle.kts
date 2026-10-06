plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.filegateway"
    database = "file_gateway_db"
}
