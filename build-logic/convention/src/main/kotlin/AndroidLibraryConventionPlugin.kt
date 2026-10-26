import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("mysystemapp.android.ktlint")
        pluginManager.apply("mysystemapp.android.detekt")
        pluginManager.apply("mysystemapp.android.jacoco")

        extensions.configure<LibraryExtension> {
            compileSdk = 36

            defaultConfig {
                minSdk = 26
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            buildFeatures {
                buildConfig = false
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            testOptions {
                unitTests.isIncludeAndroidResources = true
            }

            packaging {
                resources {
                    excludes += "/META-INF/{AL2.0,LGPL2.1}"
                }
            }
        }

        tasks.withType<Test>().configureEach {
            failOnNoDiscoveredTests.set(false)
        }

        // The checked-in platform framework.jar is emitted by the API-37/AOSP toolchain and
        // contains Java 21 class files. Android sources still target Java 17 for runtime
        // compatibility, but javac itself must be new enough to read the platform stubs on a
        // clean checkout (incremental builds previously hid this mismatch).
        val javaToolchains = extensions.getByType<JavaToolchainService>()
        tasks.withType<JavaCompile>().configureEach {
            javaCompiler.set(
                javaToolchains.compilerFor {
                    languageVersion.set(JavaLanguageVersion.of(21))
                },
            )
        }
    }
}
