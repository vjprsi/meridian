plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.archive"
    database = "archive_db"
}

// The generated data dictionary is the spec catalogue the classification scan reads at runtime.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.file("generated/docs/data-dictionary.md")) { into("meridian") }
}
