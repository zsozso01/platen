import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Pure-JVM Kotlin library (no Android dependency). Everything in core/, protocol/, backend/ and
 * transport/ uses this so it can be unit tested on a plain JVM and reused outside Android.
 *
 * Bytecode targets Java 17 and the compiler is told to only allow the Java 17 API surface, so a
 * module can never accidentally call something Android's runtime does not have.
 */
class KotlinJvmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

        extensions.configure<KotlinJvmProjectExtension> {
            explicitApi()
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                allWarningsAsErrors.set(
                    providers.gradleProperty("platen.warningsAsErrors").map(String::toBoolean).orElse(false),
                )
                freeCompilerArgs.addAll("-Xjdk-release=17", "-opt-in=kotlin.RequiresOptIn")
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed", "skipped")
                showStandardStreams = false
            }
        }

        dependencies {
            add("testImplementation", libs.lib("kotlin-test-junit5"))
            add("testImplementation", libs.lib("junit-jupiter"))
            add("testRuntimeOnly", libs.lib("junit-platform-launcher"))
        }
    }
}
