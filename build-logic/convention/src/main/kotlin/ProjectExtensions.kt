import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalStateException("Missing library alias '$alias' in libs.versions.toml") }

internal const val PLATEN_MIN_SDK = 26
internal const val PLATEN_COMPILE_SDK = 36
internal const val PLATEN_TARGET_SDK = 36

/**
 * Maven-style group derived from the module's layer, e.g. `:protocol:raster` -> `io.github.zsozso01.platen.protocol`.
 * Two modules may share a name in different layers (`protocol:raster`, `backend:raster`); the group keeps their
 * coordinates distinct so Gradle never mistakes one for the other.
 */
internal val Project.platenGroup: String
    get() = "io.github.zsozso01.platen" + (parent?.path?.takeIf { it != ":" }?.replace(':', '.') ?: "")
