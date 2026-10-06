import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * OpenAPI Generator keeps global static state, so two generations in the same Gradle JVM corrupt each other.
 * Every openApiGenerate task uses this service with maxParallelUsages = 1 (compilation stays parallel).
 */
abstract class CodegenLock : BuildService<BuildServiceParameters.None>
