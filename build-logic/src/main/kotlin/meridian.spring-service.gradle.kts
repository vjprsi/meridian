// Applied to every service under services/. Reads its contract from generated/openapi/services/<name>.openapi.yaml
// and its Flyway migrations from generated/db/<database>/, both produced by tools/generate.py.
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    id("meridian.java-conventions")
    id("org.springframework.boot")
    id("org.openapi.generator")
}

val svc = extensions.create<MeridianServiceExtension>("meridianService")
val spec = rootProject.layout.projectDirectory.file("generated/openapi/services/${project.name}.openapi.yaml")

dependencies {
    "implementation"(project(":libs:meridian-common"))
    "implementation"(project(":libs:meridian-web"))
    "implementation"(project(":libs:meridian-events"))
    "implementation"("org.springframework.boot:spring-boot-starter-webmvc")
    "implementation"("org.springframework.boot:spring-boot-starter-validation")
    "implementation"("org.springframework.boot:spring-boot-starter-actuator")
    "implementation"("org.springframework.boot:spring-boot-starter-jdbc")
    "implementation"("org.springframework.boot:spring-boot-starter-flyway")
    "implementation"("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    "implementation"("org.springframework.boot:spring-boot-starter-kafka")
    "implementation"("org.springframework.boot:spring-boot-starter-opentelemetry")
    "runtimeOnly"("org.flywaydb:flyway-database-postgresql")
    "runtimeOnly"("org.postgresql:postgresql")
    "testImplementation"(project(":libs:meridian-test"))
}

val codegenLock = gradle.sharedServices.registerIfAbsent("openapiCodegenLock", CodegenLock::class) {
    maxParallelUsages = 1
}

val openApiGenerate = tasks.named<GenerateTask>("openApiGenerate") {
    usesService(codegenLock)
    generatorName = "spring"
    templateDir.set(rootProject.layout.projectDirectory.dir("build-logic/openapi-templates/spring").asFile.absolutePath)
    inputSpec = spec.asFile.toURI().toString()
    outputDir = layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath
    apiPackage = svc.basePackage.map { "$it.api.generated" }
    modelPackage = svc.basePackage.map { "$it.api.generated.model" }
    configOptions = mapOf(
        "interfaceOnly" to "true",
        "useSpringBoot3" to "true",
        "useJakartaEe" to "true",
        "useTags" to "true",
        "openApiNullable" to "false",
        "documentationProvider" to "none",
        "annotationLibrary" to "none",
        "skipDefaultInterface" to "false",
        "useResponseEntity" to "true",
        "dateLibrary" to "java8",
        "serializableModel" to "false",
        "generatedConstructorWithRequiredArgs" to "false",
        "containerDefaultToNull" to "true",
    )
    typeMappings = mapOf("string+decimal" to "BigDecimal")
    importMappings = mapOf("BigDecimal" to "java.math.BigDecimal")
    globalProperties = mapOf("apiDocs" to "false", "modelDocs" to "false", "apiTests" to "false", "modelTests" to "false")
}

the<SourceSetContainer>()["main"].java.srcDir(openApiGenerate.map { "${it.outputDir.get()}/src/main/java" })
tasks.named("compileJava") { dependsOn(openApiGenerate) }

// Flyway migrations come straight from the generated DDL (single source of truth).
tasks.named<ProcessResources>("processResources") {
    from(svc.database.map { rootProject.layout.projectDirectory.dir("generated/db/$it") }) { into("db/migration") }
    // Service directory (names -> ports) for ServiceClient.
    from(rootProject.layout.projectDirectory.file("generated/services.json")) { into("meridian") }
}
