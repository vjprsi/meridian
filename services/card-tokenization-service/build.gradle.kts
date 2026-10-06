plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.cardtokenization"
    database = "card_token_db"
}
