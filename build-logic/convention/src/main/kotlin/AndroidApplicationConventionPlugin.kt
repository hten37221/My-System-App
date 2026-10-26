import com.android.build.api.dsl.ApplicationExtension
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

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("mysystemapp.android.ktlint")
        pluginManager.apply("mysystemapp.android.detekt")
        pluginManager.apply("mysystemapp.android.jacoco")

        extensions.configure<ApplicationExtension> {
            compileSdk = 36

            defaultConfig {
                minSdk = 26
                targetSdk = 36
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                vectorDrawables.useSupportLibrary = true
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

        // See AndroidLibraryConventionPlugin: platform framework stubs are Java 21 class files,
        // while the application bytecode target remains Java 17.
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
