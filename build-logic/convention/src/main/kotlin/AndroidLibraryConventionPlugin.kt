import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/** Android library module (platform/ layer). Kotlin support is built into AGP 9. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        group = platenGroup

        extensions.configure<LibraryExtension> {
            compileSdk = PLATEN_COMPILE_SDK
            defaultConfig {
                minSdk = PLATEN_MIN_SDK
                consumerProguardFiles("consumer-rules.pro")
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
