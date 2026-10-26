import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        dependencies {
            add("implementation", libs.findLibrary("dagger-hilt-android").get())
            // Hilt 2.59.x generates component maps backed by Guava ImmutableMap.
            // Keep the Android runtime in every final Hilt artifact, not only on
            // the annotation processor classpath.
            add("implementation", libs.findLibrary("guava-android").get())
            add("ksp", libs.findLibrary("dagger-hilt-compiler").get())
        }
    }
}
