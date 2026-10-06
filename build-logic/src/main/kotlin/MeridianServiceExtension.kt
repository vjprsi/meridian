import org.gradle.api.provider.Property

/** Per-service settings declared in services/<name>/build.gradle.kts. */
abstract class MeridianServiceExtension {
    abstract val basePackage: Property<String>
    abstract val database: Property<String>
}
