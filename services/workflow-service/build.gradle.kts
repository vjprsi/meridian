plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.workflow"
    database = "workflow_db"
}
