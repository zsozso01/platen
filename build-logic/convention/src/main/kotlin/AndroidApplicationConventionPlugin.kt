import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/** The :app module. Build types, signing and versioning are configured in app/build.gradle.kts. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            compileSdk = PLATEN_COMPILE_SDK
            defaultConfig {
                minSdk = PLATEN_MIN_SDK
                targetSdk = PLATEN_TARGET_SDK
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }

        tasks.withType<Test>().configureEach { useJUnitPlatform() }
        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions.allWarningsAsErrors.set(
                providers.gradleProperty("platen.warningsAsErrors").map(String::toBoolean).orElse(false),
            )
        }

        dependencies {
            add("testImplementation", libs.lib("kotlin-test-junit5"))
            add("testImplementation", libs.lib("junit-jupiter"))
            add("testRuntimeOnly", libs.lib("junit-platform-launcher"))
        }
    }
}
