plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.loanorigination"
    database = "loan_origination_db"
}
