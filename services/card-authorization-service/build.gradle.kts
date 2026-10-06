plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.cardauthorization"
    database = "card_auth_db"
}
